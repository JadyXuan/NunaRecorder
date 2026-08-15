package com.example.nunarecorder.web

import com.example.nunarecorder.enroll.EnrollmentCode
import org.json.JSONObject

/**
 * 内置浏览器里「哪个站点该填哪一套账号」。
 *
 * 用户 2026-08-15 的原始需求：「手机 app 内置一个浏览器来打开标注 UI，入组后自动保存
 * sso 账号密码、而且一次登陆后也免密……避免反复输入 sso 密码和自己的账户密码」。
 * 凭据本来就已经在 App 里了（扫码入组时随入组码一起下发），这里只决定**给谁**。
 *
 * ## 安全边界：按主机名精确匹配，匹配不上就什么都不注入
 *
 * 实测这条链路上有**三个不同的主机**：
 *
 * | 主机 | 是什么 | 填哪套 |
 * | --- | --- | --- |
 * | 入组码里 `web` 的主机（`nuna-audio-lab.cuhkaiot.com`） | 标注站 | **参与者账号** |
 * | `sso-portal-*.cuhkaiot.com` | 网关的 Authentik 登录页 | **网关 SSO 账号** |
 * | 其它任何主机 | —— | **什么都不填** |
 *
 * 参与者在浏览器里点到站外（外链、搜索、广告）是随时会发生的，而一次填错
 * 就是把网关口令交给了别人的站点。**所以这里是白名单，不是黑名单**，
 * 而且是**精确主机**，不做「包含 cuhkaiot.com 就算自己人」这种前缀匹配——
 * `cuhkaiot.com.evil.example` 会通过前缀匹配。
 *
 * 这个类不碰 WebView，可直接跑 JVM 单测。
 */
object LoginAutofill {

    /** 网关 SSO 登录页的主机；实测是 Authentik，`/if/flow/...` */
    const val SSO_HOST_SUFFIX = ".cuhkaiot.com"
    private const val SSO_HOST_PREFIX = "sso-portal"

    enum class Target { PARTICIPANT, SSO, NONE }

    /**
     * 这个主机该填哪一套。
     *
     * @param host 当前页面的主机名（不含端口）
     * @param webHost 入组码里标注站的主机名
     */
    fun targetFor(host: String?, webHost: String?): Target {
        val h = host?.lowercase()?.trim().orEmpty()
        if (h.isEmpty()) return Target.NONE
        val w = webHost?.lowercase()?.trim().orEmpty()
        if (w.isNotEmpty() && h == w) return Target.PARTICIPANT
        // SSO 门户的主机名带机房编号（实测 sso-portal-hk51），所以只能前缀 + 后缀匹配。
        // 两头都要卡：只卡后缀的话任何 *.cuhkaiot.com 都能拿到网关口令。
        if (h.startsWith(SSO_HOST_PREFIX) && h.endsWith(SSO_HOST_SUFFIX)) return Target.SSO
        return Target.NONE
    }

    /** 这一套凭据存不存在。缺 SSO 账号的入组码是合法的（内网直连时不需要过网关）。 */
    fun credentialsFor(enrollment: EnrollmentCode, target: Target): Pair<String, String>? {
        val login = enrollment.login ?: return null
        return when (target) {
            Target.PARTICIPANT -> login.username to login.password
            Target.SSO -> {
                val u = login.ssoUsername ?: return null
                val p = login.ssoPassword ?: return null
                u to p
            }
            Target.NONE -> null
        }
    }

    /**
     * 生成注入用的 JS。
     *
     * 两个站的表单形状实测都是 `input[name=username]` + `input[name=password]`
     * （标注站 `app/frontend/templates/auth/login.html`，SSO 是 Authentik）。
     * 仍然按 `type=password` 兜底，因为 Authentik 的流程是分步的，
     * 某一步可能只渲染其中一个字段。
     *
     * **不碰 `name=code`**：那是一次性验证码（TOTP），我们没有，
     * 往里填任何东西都只会让登录失败。
     *
     * @param submit 是否顺带提交。见 [shouldAutoSubmit] 关于为什么不是每次都提交。
     */
    fun fillScript(username: String, password: String, submit: Boolean): String {
        val u = JSONObject.quote(username)
        val p = JSONObject.quote(password)
        return """
        (function () {
          var u = $u, p = $p, submit = $submit;
          function setVal(el, v) {
            if (!el) return false;
            var setter = Object.getOwnPropertyDescriptor(
              window.HTMLInputElement.prototype, 'value').set;
            setter.call(el, v);
            // 直接改 .value 不会触发前端框架的双向绑定，登录页多半读不到
            el.dispatchEvent(new Event('input', { bubbles: true }));
            el.dispatchEvent(new Event('change', { bubbles: true }));
            return true;
          }
          var pw = document.querySelector('input[type=password]');
          var un = document.querySelector('input[name=username]')
                || document.querySelector('input[autocomplete=username]')
                || document.querySelector('input#userId');
          var filled = false;
          if (un && !un.value) filled = setVal(un, u) || filled;
          if (pw && !pw.value) filled = setVal(pw, p) || filled;
          if (!filled) return 'nothing-to-fill';
          if (submit) {
            var form = (pw && pw.form) || (un && un.form);
            if (form) {
              var btn = form.querySelector('button[type=submit],input[type=submit]');
              if (btn) { btn.click(); } else { form.submit(); }
              return 'filled-and-submitted';
            }
          }
          return 'filled';
        })();
        """.trimIndent()
    }

    /**
     * 要不要自动提交。
     *
     * 用户要的是"自动完成认证"，但**同一个主机在一次浏览器会话里只自动提交一次**：
     * 如果凭据是错的，自动提交会变成一个无限重试循环，
     * 而多数网关对连续失败是**锁账号**的——30 个参与者共用同一个网关访客账号，
     * 锁一次就是所有人都进不去。
     *
     * 第一次之后就只填不提交，让参与者自己按，失败原因他也看得见。
     */
    fun shouldAutoSubmit(host: String, alreadyTried: Set<String>): Boolean = host !in alreadyTried
}
