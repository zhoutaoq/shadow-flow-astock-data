/* ============ 公共前端工具 ============ */

/* ============ 运行模式 ============ */
/**
 * 由 /api/mode 填充。两种模式共用一套页面，导航按「是否依赖库表」裁剪：
 * 无数据库模式下不生成「数据更新」整组与「调用日志」「接口调试」，
 * 只剩 概览 / 分析回测（股票列表 · K线分析 · 策略回测）/ 系统（实时通知配置 · 问题反馈）。
 * 取不到接口时按「数据库模式」渲染，避免一次请求失败导致整页没导航。
 */
const MODE = {
    dbEnabled: true,
    noDb: false,
    maxSpanDays: 1200,
    backtestPersistent: true
};

/**
 * 只拉一次：缓存的是 Promise 而不是布尔标记。
 *
 * <p>若只记布尔，首个调用方（页面内联脚本）刚发起 fetch、尚未返回时，
 * DOMContentLoaded 里的第二个调用方会立刻拿到「还没写入 noDb」的 MODE，
 * renderNav() 就按默认的数据库模式渲染出全量菜单 —— 线上的菜单竞态 bug 正是它。
 * 缓存 Promise 后，所有调用方等的是同一次 fetch，拿到的一定是写入后的 MODE。
 */
let _modePromise = null;
function loadMode() {
    if (!_modePromise) {
        _modePromise = (async function () {
            try {
                const res = await fetch('/api/mode');
                const json = await res.json();
                if (json && json.success && json.data) {
                    Object.assign(MODE, json.data);
                    MODE.noDb = !json.data.dbEnabled;
                }
            } catch (e) {
                // 拉取失败保持默认，按数据库模式渲染
            }
            return MODE;
        })();
    }
    return _modePromise;
}

/* ============ Cookie 小工具 ============ */

function setCookie(name, value, days) {
    const d = new Date();
    d.setTime(d.getTime() + days * 86400000);
    document.cookie = name + '=' + encodeURIComponent(value)
        + ';expires=' + d.toUTCString() + ';path=/;SameSite=Lax';
}

function getCookie(name) {
    const m = document.cookie.match(new RegExp('(?:^|; )' + name + '=([^;]*)'));
    return m ? decodeURIComponent(m[1]) : '';
}

function delCookie(name) {
    document.cookie = name + '=;expires=Thu, 01 Jan 1970 00:00:00 GMT;path=/;SameSite=Lax';
}

/* ============ 令牌 ============ */
/**
 * v1.5 起行情数据服务令牌完全由服务端按用户维护，浏览器不再持有令牌。
 *
 * <p>「实时通知配置」页保存的令牌记在当前登录用户名下，抓数时后端取「发起这一请求的人」
 * 自己的令牌；该用户没配过时回落到系统级默认令牌。所以前端既不存 cookie 令牌、
 * 也没有内置默认值与兜底填写框，更不发 {@code X-Waizao-Token} 请求头 ——
 * 换浏览器 / 换设备登录同一账号，用的是同一份令牌。
 */

/* ============ 账号登录态 ============ */
/**
 * 登录令牌放 cookie（7 天），每个请求用 X-Auth-Token 带过去。
 *
 * <p>令牌本身在服务端内存里，进程重启即失效；这时接口会回 401，
 * 由 {@link onUnauthorized} 统一清掉本地令牌并跳登录页。
 */
const AUTH_COOKIE = 'shadowflow_token';
/**
 * v1.6.0 之前的 cookie 名（品牌改名前的 {@code jn_} 前缀）。
 *
 * <p>只用于<b>读取兜底</b>：老用户浏览器里还留着旧名的令牌，认下来就地迁到新名，
 * 不至于因为一次改名把所有人踢下线。迁移完旧 cookie 立即删掉。
 */
const AUTH_COOKIE_LEGACY = 'jn_token';
const AUTH_COOKIE_DAYS = 7;

/** 当前登录态。各页面只读它，不自己发 /api/auth/me */
const ME = {
    loggedIn: false,
    user: null,
    /** 代理登录中：{id, username} 是坐在后面的那个超管；不在代理中为 null */
    impersonator: null,
    registrationEnabled: true,
    /** GitHub 三方登录是否开通（服务端凭据齐才 true），登录弹窗据此显隐 GitHub 按钮 */
    githubLoginEnabled: false,
    /**
     * 邮箱验证码规则：{configured, requiredOnRegister, resendSeconds, ttlMinutes, codeLength}。
     * 注册标签页据此决定显不显示验证码行、倒计时多少秒 —— 与服务端校验用的是同一份参数。
     */
    emailCode: {
        configured: false,
        requiredOnRegister: true,
        resendSeconds: 60,
        ttlMinutes: 10,
        codeLength: 6
    },
    rules: {minPasswordLength: 6, requireLetterAndDigit: true, usernamePattern: 'email'}
};

/** 登录弹窗的即时状态：当前落在登录还是注册、这一次打开要回调谁 */
let _authMode = 'login';
let _authCb = {};

function authToken() {
    const t = getCookie(AUTH_COOKIE);
    if (t) {
        return t;
    }
    // 改名平滑过渡：旧 cookie 里的令牌仍然有效，读到就搬成新名
    const legacy = getCookie(AUTH_COOKIE_LEGACY);
    if (legacy) {
        setCookie(AUTH_COOKIE, legacy, AUTH_COOKIE_DAYS);
        delCookie(AUTH_COOKIE_LEGACY);
        return legacy;
    }
    return '';
}

function setAuthToken(token) {
    if (token) {
        setCookie(AUTH_COOKIE, token, AUTH_COOKIE_DAYS);
    } else {
        delCookie(AUTH_COOKIE);
    }
}

function clearAuthToken() {
    delCookie(AUTH_COOKIE);
}

/**
 * 拉一次登录态。与 loadMode 同样缓存 Promise —— 否则导航栏和页面脚本各发一次，
 * 两次结果还可能一前一后，菜单会闪。
 */
let _mePromise = null;
function loadMe(force) {
    if (force) {
        _mePromise = null;
    }
    if (!_mePromise) {
        _mePromise = (async function () {
            try {
                const res = await fetch('/api/auth/me', {headers: authHeaders()});
                const json = await res.json();
                if (json && json.success && json.data) {
                    ME.loggedIn = !!json.data.loggedIn;
                    ME.user = json.data.user || null;
                    ME.impersonator = json.data.impersonator || null;
                    ME.registrationEnabled = json.data.registrationEnabled !== false;
                    ME.githubLoginEnabled = json.data.githubLoginEnabled === true;
                    if (json.data.emailCode) {
                        ME.emailCode = Object.assign(ME.emailCode, json.data.emailCode);
                    }
                    if (json.data.rules) {
                        ME.rules = json.data.rules;
                    }
                }
            } catch (e) {
                // 拉不到就按未登录渲染：匿名可用的页面照常工作
            }
            return ME;
        })();
    }
    return _mePromise;
}

function authHeaders() {
    const t = authToken();
    return t ? {'X-Auth-Token': t} : {};
}

function isLogin() {
    return ME.loggedIn;
}

function isAdmin() {
    return !!(ME.user && ME.user.admin);
}

/** 需要登录的页面在开头调它；未登录就地弹登录框，登完继续，放弃则回首页 */
async function requireLogin() {
    await loadMe();
    if (ME.loggedIn) {
        return true;
    }
    return await new Promise(resolve => {
        openAuthModal({
            onSuccess: () => resolve(true),
            onCancel: () => {
                resolve(false);
                location.href = '/';
            }
        });
    });
}

/**
 * 接口回 401：令牌已在服务端失效，本地清掉并就地弹登录框。
 *
 * <p>登录成功后整页重载 —— 页面上原有的数据都是失效态，重跑一遍初始化最省事。
 */
function onUnauthorized() {
    const first = !ME.loggedIn;
    clearAuthToken();
    ME.loggedIn = false;
    ME.user = null;
    ME.impersonator = null;
    paintUserArea();
    if (first) {
        toast('登录已失效，请重新登录', 'err');
    }
    openAuthModal({mode: 'login', onSuccess: () => location.reload()});
}

/* ============ 登录 / 注册弹窗 ============ */
/**
 * 全站唯一的登录入口：登录与注册都在弹窗里完成，不再单开一页。
 *
 * <p>顶栏「登录 / 注册」、需要登录的菜单、受保护页面、令牌失效都调它。
 * 弹窗只有一份 DOM，反复开关只重置表单，回调按每次打开时的参数走。
 *
 * @param {object}   [opt]
 * @param {string}   [opt.mode]      'login' | 'register'，决定落在哪个标签页
 * @param {function} [opt.onSuccess] 登录 / 注册成功回调，参数为当前用户
 * @param {function} [opt.onCancel]  用户主动关掉弹窗（取消 / 点遮罩 / Esc）
 */
function openAuthModal(opt) {
    const o = opt || {};
    _authCb = {onSuccess: o.onSuccess || null, onCancel: o.onCancel || null};

    let mask = document.getElementById('authMask');
    if (!mask) {
        mask = document.createElement('div');
        mask.className = 'mask';
        mask.id = 'authMask';
        mask.innerHTML = '<div class="modal modal-auth" role="dialog" aria-modal="true" aria-label="登录或注册">'
            + '<div class="auth-head">'
            + '<span class="auth-brand">' + brandInner() + '</span>'
            + '</div>'
            + '<div class="auth-tabs">'
            + '<button class="auth-tab" id="amTabLogin" type="button">登录</button>'
            + '<button class="auth-tab" id="amTabReg" type="button">注册</button>'
            + '</div>'
            + '<div class="auth-alert" id="amAlert">' + ICON.err + '<span id="amAlertTxt"></span></div>'
            + '<div class="auth-field" id="amUserField">'
            + '<label for="amUser" id="amUserLabel">账号</label>'
            + '<div class="auth-input-wrap">'
            + '<input type="text" id="amUser" autocomplete="username" spellcheck="false">'
            + '</div>'
            + '</div>'
            + '<div class="auth-field" id="amPwdField">'
            + '<label for="amPwd">密码</label>'
            + '<div class="auth-input-wrap has-toggle">'
            + '<input type="password" id="amPwd" autocomplete="current-password">'
            + '<button class="auth-eye" type="button" data-for="amPwd" aria-label="显示密码">' + ICON.eye + '</button>'
            + '</div>'
            + '<div class="auth-strength" id="amStrength" hidden><i></i><i></i><i></i></div>'
            + '</div>'
            + '<div class="auth-field" id="amPwd2Field" style="display:none">'
            + '<label for="amPwd2">确认密码</label>'
            + '<div class="auth-input-wrap has-toggle">'
            + '<input type="password" id="amPwd2" autocomplete="new-password">'
            + '<button class="auth-eye" type="button" data-for="amPwd2" aria-label="显示密码">' + ICON.eye + '</button>'
            + '</div>'
            + '</div>'
            + '<div class="auth-field" id="amCodeField" style="display:none">'
            + '<label for="amCode">邮箱验证码</label>'
            + '<div class="auth-code-row">'
            + '<div class="auth-input-wrap">'
            + '<input type="text" id="amCode" inputmode="numeric" autocomplete="one-time-code" spellcheck="false" maxlength="6">'
            + '</div>'
            + '<button class="auth-code-btn" id="amCodeSend" type="button">获取验证码</button>'
            + '</div>'
            + '<div class="auth-code-hint" id="amCodeHint"></div>'
            + '</div>'
            + '<button class="auth-submit" id="amSubmit" type="button">登录</button>'
            + '<div class="auth-oauth" id="amOauth" hidden>'
            + '<div class="auth-oauth-sep"><i></i><span>或</span><i></i></div>'
            + '<button class="auth-github" id="amGithub" type="button">' + ICON.github + '使用 GitHub 登录</button>'
            + '</div>'
            + '</div>';
        document.body.appendChild(mask);

        mask.querySelector('#amTabLogin').addEventListener('click', () => switchAuthMode('login'));
        mask.querySelector('#amTabReg').addEventListener('click', () => switchAuthMode('register'));
        mask.querySelector('#amSubmit').addEventListener('click', submitAuthModal);
        mask.querySelector('#amGithub').addEventListener('click', startGithubLogin);
        mask.querySelector('#amCodeSend').addEventListener('click', sendAuthCode);
        mask.querySelectorAll('.auth-eye').forEach(btn => {
            btn.addEventListener('click', () => togglePwdVisible(btn.getAttribute('data-for')));
        });
        mask.addEventListener('mousedown', e => {
            if (e.target === mask) {
                closeAuthModal(true);
            }
        });
        // 注册标签页填了邮箱后，验证码行才出现
        mask.querySelector('#amUser').addEventListener('input', syncCodeField);
        ['amUser', 'amPwd', 'amPwd2', 'amCode'].forEach(id => {
            const el = mask.querySelector('#' + id);
            el.addEventListener('keydown', e => {
                if (e.key === 'Enter') {
                    e.preventDefault();
                    submitAuthModal();
                }
            });
            // 边输边消错：报错后一动键盘就把红字收掉，不用等下一次提交
            el.addEventListener('input', () => setAuthError(''));
        });
        mask.querySelector('#amPwd').addEventListener('input', renderStrength);
        document.addEventListener('keydown', e => {
            if (e.key === 'Escape' && mask.classList.contains('show')) {
                closeAuthModal(true);
            }
        });
    }
    ['amUser', 'amPwd', 'amPwd2', 'amCode'].forEach(id => {
        mask.querySelector('#' + id).value = '';
    });
    resetCodeButton();
    switchAuthMode(o.mode === 'register' ? 'register' : 'login');
    // GitHub 按钮只在服务端开通了三方登录时出现（未配 OAuth 凭据就别给用户一个点了报错的按钮）
    mask.querySelector('#amOauth').hidden = !ME.githubLoginEnabled;
    mask.classList.add('show');
    setTimeout(() => mask.querySelector('#amUser').focus(), 60);
}

/** GitHub 授权跳转是否已发起：跳转前的空白期里连点会重复走 OAuth */
let _ghJumping = false;

/**
 * 跳 GitHub 授权页。
 *
 * <p>returnTo 记下当前页路径，授权完成回来还落在原地（服务端只认站内相对路径）。
 * 整页跳转离开是 OAuth 授权码流程的常态，回来时走 fragment 收令牌（见 handleAuthFragment）。
 *
 * <p>点击到页面真正跳走之间有一段空窗（服务端 302 + github.com 握手），网络慢时
 * 好几秒没反应，用户必然再点。这里先把按钮改成「正在跳转」并锁住，
 * 口令登录按钮一并禁用，免得两条登录路同时发起；跳转失败则 10 秒后自动解锁。
 */
function startGithubLogin() {
    if (_ghJumping) {
        return;
    }
    _ghJumping = true;
    const btn = document.getElementById('amGithub');
    if (btn) {
        btn.classList.add('loading');
        btn.disabled = true;
        btn.innerHTML = '<span class="auth-spin"></span>正在跳转 GitHub…';
    }
    const submit = document.getElementById('amSubmit');
    if (submit) {
        submit.disabled = true;
    }
    setTimeout(resetGithubBtn, 10000);
    const back = location.pathname + location.search;
    location.href = '/api/auth/github/login?returnTo=' + encodeURIComponent(back);
}

/** 把 GitHub 按钮复位。跳转没走成（被拦、断网）或用户从后退回来时用 */
function resetGithubBtn() {
    _ghJumping = false;
    const btn = document.getElementById('amGithub');
    if (btn) {
        btn.classList.remove('loading');
        btn.disabled = false;
        btn.innerHTML = ICON.github + '使用 GitHub 登录';
    }
    const submit = document.getElementById('amSubmit');
    if (submit) {
        submit.disabled = false;
    }
}

// 后退回到本页（bfcache）时按钮还停在加载态，得复位才能再点
window.addEventListener('pageshow', function () {
    if (_ghJumping) {
        resetGithubBtn();
    }
});

/**
 * 收 OAuth 回跳：地址栏 fragment 里的 {@code #token=}（成功）或 {@code #authError=}（失败）。
 *
 * <p>令牌走 fragment 不走 query —— 不进服务端访问日志、不进 Referer。
 * 收到就写 cookie 并清掉 fragment，页面随后按正常登录态渲染；
 * 失败原因弹一次提示后同样清掉，不留脏地址。
 *
 * @returns {boolean} 本次是否处理了一个回跳
 */
function handleAuthFragment() {
    const h = location.hash || '';
    if (h.indexOf('#token=') === 0) {
        const token = decodeURIComponent(h.slice('#token='.length));
        // 清地址栏在前：token 不该在历史记录里多待一秒
        history.replaceState(null, '', location.pathname + location.search);
        if (token) {
            setAuthToken(token);
            // 成功了就把自动重试的标记清掉，下次失败还能再自动试一次
            sessionStorage.removeItem('ghAuthRetry');
            return true;
        }
        return false;
    }
    if (h.indexOf('#authError=') === 0) {
        const msg = decodeURIComponent(h.slice('#authError='.length)) || 'GitHub 登录失败，请稍后再试';
        history.replaceState(null, '', location.pathname + location.search);
        // 网络类失败自动再来一次：换 token 失败时 GitHub 没消费掉授权码，
        // 服务器到 github.com 又是成块抖动，重走一遍授权往往就过了。只自动试一次。
        if (/超时|无法连接|无法解析|通信失败/.test(msg) && !sessionStorage.getItem('ghAuthRetry')) {
            sessionStorage.setItem('ghAuthRetry', '1');
            toast('网络抖动，正在重试 GitHub 登录…', 'ok');
            location.href = '/api/auth/github/login';
            return false;
        }
        sessionStorage.removeItem('ghAuthRetry');
        toast(msg, 'err');
        return false;
    }
    return false;
}

/** 登录 / 注册标签页切换；注册被关掉时不给切过去 */
function switchAuthMode(m) {
    const reg = m === 'register' && ME.registrationEnabled !== false;
    _authMode = reg ? 'register' : 'login';
    const mask = document.getElementById('authMask');
    if (!mask) {
        return;
    }
    const guest = ME.registrationEnabled !== false;
    mask.querySelector('#amTabReg').style.display = guest ? '' : 'none';
    mask.querySelector('#amTabLogin').classList.toggle('active', !reg);
    mask.querySelector('#amTabReg').classList.toggle('active', reg);
    mask.querySelector('#amSubmit').textContent = reg ? '注册并登录' : '登录';
    mask.querySelector('#amPwd2Field').style.display = reg ? '' : 'none';
    mask.querySelector('#amStrength').hidden = !reg;

    // 登录只要求账号非空：超管账号是 admin，不是邮箱，用邮箱格式卡它等于把超管挡在门外。
    // 邮箱格式只在注册时校验，与后端 AuthService.checkUsername 的适用范围一致。
    // type=email 也必须能被 base 的 input 样式命中，否则注册态账号框会退化成裸控件。
    const userEl = mask.querySelector('#amUser');
    userEl.setAttribute('type', reg ? 'email' : 'text');
    mask.querySelector('#amUserLabel').textContent = reg ? '邮箱' : '账号';
    mask.querySelector('#amPwd').setAttribute('autocomplete', reg ? 'new-password' : 'current-password');
    setAuthError('');
    renderStrength();
    syncCodeField();
}

/** 账号看起来是不是邮箱（含 @ 即可）：发注册验证码前先挡一道，省得白跑请求 */
function isEmailish(v) {
    return /^[^@\s]+@[^@\s]+\.[A-Za-z]{2,}$/.test(String(v || '').trim());
}

/** 验证码冷却剩余秒数 / 是否正在发送 / 倒计时句柄（弹窗只有一份，全局一份状态就够） */
let _codeLeft = 0;
let _codeSending = false;
let _codeTimer = null;

/**
 * 验证码行只在注册标签页出现 —— 登录只要邮箱 + 密码，不收码也不校验。
 *
 * <p>切标签页、开关弹窗都会重跑本函数，登录态那一行保证是隐藏的。
 */
function syncCodeField() {
    const mask = document.getElementById('authMask');
    if (!mask) {
        return;
    }
    const need = _authMode === 'register' && ME.emailCode.requiredOnRegister !== false;
    mask.querySelector('#amCodeField').style.display = need ? '' : 'none';
    if (!need) {
        _codeLeft = 0;
        stopCodeTimer();
        setCodeHint('');
    } else if (!ME.emailCode.configured) {
        setCodeHint('邮件服务未配置，暂时无法获取验证码');
    } else if (_codeLeft === 0) {
        setCodeHint('');
    }
    paintCodeButton();
}

/** 按钮文案：发送中 / 倒计时 / 可点击，三态只在这一个地方画 */
function paintCodeButton() {
    const mask = document.getElementById('authMask');
    if (!mask) {
        return;
    }
    const btn = mask.querySelector('#amCodeSend');
    if (!btn) {
        return;
    }
    if (_codeSending) {
        btn.disabled = true;
        btn.innerHTML = '<span class="auth-spin"></span>发送中…';
        return;
    }
    if (_codeLeft > 0) {
        btn.disabled = true;
        btn.textContent = _codeLeft + ' 秒后重发';
        return;
    }
    btn.disabled = !ME.emailCode.configured;
    btn.textContent = '获取验证码';
}

function setCodeHint(text) {
    const mask = document.getElementById('authMask');
    if (!mask) {
        return;
    }
    const el = mask.querySelector('#amCodeHint');
    el.textContent = text || '';
    el.classList.toggle('show', !!text);
}

function stopCodeTimer() {
    if (_codeTimer) {
        clearInterval(_codeTimer);
        _codeTimer = null;
    }
}

/** 发码成功后开始倒计时；秒数取服务端的 resendSeconds，前端不另写一个 60 */
function startCodeCountdown() {
    stopCodeTimer();
    _codeLeft = Math.max(1, Number(ME.emailCode.resendSeconds) || 60);
    paintCodeButton();
    _codeTimer = setInterval(() => {
        _codeLeft = Math.max(0, _codeLeft - 1);
        if (_codeLeft === 0) {
            stopCodeTimer();
            setCodeHint('');
        }
        paintCodeButton();
    }, 1000);
}

/** 关闭 / 重开弹窗时把按钮与倒计时复位 */
function resetCodeButton() {
    stopCodeTimer();
    _codeLeft = 0;
    _codeSending = false;
    paintCodeButton();
    setCodeHint('');
}

/**
 * 发注册验证码。邮箱已注册时服务端会直接拒绝，省一封无用的信。
 */
async function sendAuthCode() {
    const mask = document.getElementById('authMask');
    if (!mask || _codeSending) {
        return;
    }
    if (_authMode !== 'register') {
        return;
    }
    const user = mask.querySelector('#amUser').value.trim();
    if (!user) {
        setAuthError('请先填写邮箱');
        markAuthField('amUserField');
        mask.querySelector('#amUser').focus();
        return;
    }
    if (!isEmailish(user)) {
        setAuthError('这个邮箱格式不对，检查一下');
        markAuthField('amUserField');
        mask.querySelector('#amUser').focus();
        return;
    }
    _codeSending = true;
    setAuthError('');
    paintCodeButton();
    try {
        const r = await post('/api/auth/email-code', {username: user});
        if (!r || !r.success) {
            // 失败就复位：服务端有 60 秒冷却，真刷得太快它会自己拦
            _codeSending = false;
            paintCodeButton();
            return;
        }
        _codeSending = false;
        setCodeHint(r.message || '验证码已发送');
        startCodeCountdown();
        mask.querySelector('#amCode').focus();
    } catch (e) {
        _codeSending = false;
        paintCodeButton();
    }
}

/** 关掉弹窗：cancel 为真表示是用户主动放弃，要回调 onCancel */
function closeAuthModal(cancel) {
    const mask = document.getElementById('authMask');
    if (mask) {
        mask.classList.remove('show');
    }
    // 倒计时跟着弹窗一起停：不留一个对着隐藏 DOM 空转的 interval
    stopCodeTimer();
    const cb = _authCb;
    _authCb = {};
    if (cancel && cb.onCancel) {
        cb.onCancel();
    }
}

/** 密码显隐：同一颗按钮在两个图标之间切 */
function togglePwdVisible(id) {
    const mask = document.getElementById('authMask');
    if (!mask) {
        return;
    }
    const input = mask.querySelector('#' + id);
    const btn = mask.querySelector('.auth-eye[data-for="' + id + '"]');
    const show = input.type === 'password';
    input.type = show ? 'text' : 'password';
    btn.innerHTML = show ? ICON.eyeOff : ICON.eye;
    btn.setAttribute('aria-label', show ? '隐藏密码' : '显示密码');
    input.focus();
}

/** 注册时的密码强度：只看长度与字符种类，够用即可 */
function renderStrength() {
    const mask = document.getElementById('authMask');
    if (!mask) {
        return;
    }
    const bar = mask.querySelector('#amStrength');
    const pwd = mask.querySelector('#amPwd').value;
    let score = 0;
    if (pwd.length >= 6) {
        score++;
    }
    if (pwd.length >= 8 && /[A-Za-z]/.test(pwd) && /\d/.test(pwd)) {
        score++;
    }
    if (score >= 2 && /[^A-Za-z0-9]/.test(pwd)) {
        score++;
    }
    if (pwd.length >= 14 && score >= 2) {
        score = 3;
    }
    bar.className = 'auth-strength' + (pwd && score ? ' s' + score : '');
}

/** 弹窗内的错误提示：show 时用错色，success 时用绿色（如注册成功） */
function setAuthError(text, success) {
    const mask = document.getElementById('authMask');
    if (!mask) {
        return;
    }
    const box = mask.querySelector('#amAlert');
    mask.querySelector('#amAlertTxt').textContent = text || '';
    box.classList.toggle('show', !!text);
    box.classList.toggle('auth-ok', !!success);
    box.querySelector('svg').outerHTML = success ? ICON.ok : ICON.err;
}

/** 标记某个字段校验不通过，错误消失时自动收掉 */
function markAuthField(id) {
    const mask = document.getElementById('authMask');
    if (!mask) {
        return;
    }
    mask.querySelectorAll('.auth-field').forEach(f => f.classList.remove('invalid'));
    if (id) {
        const f = mask.querySelector('#' + id);
        if (f) {
            f.classList.add('invalid');
        }
    }
}

/**
 * 提交登录 / 注册。规则与后端 AuthService 一致：邮箱做用户名、密码不少于 6 位且含字母和数字。
 * 先在前端挡一道，省得白跑一次请求。
 */
async function submitAuthModal() {
    const mask = document.getElementById('authMask');
    if (!mask) {
        return;
    }
    const reg = _authMode === 'register';
    const user = mask.querySelector('#amUser').value.trim();
    const pwd = mask.querySelector('#amPwd').value;
    const min = (ME.rules && ME.rules.minPasswordLength) || 6;

    const fail = (msg, field) => {
        setAuthError(msg);
        markAuthField(field);
        const el = field && mask.querySelector('#' + field + ' input');
        if (el) {
            el.focus();
        }
    };

    if (!user) {
        return fail(reg ? '请填写邮箱' : '请填写账号', 'amUserField');
    }
    if (reg && !/^[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}$/.test(user)) {
        return fail('这个邮箱格式不对，检查一下', 'amUserField');
    }
    if (!pwd) {
        return fail('请填写密码', 'amPwdField');
    }
    if (reg) {
        if (pwd.length < min) {
            return fail('密码至少 ' + min + ' 位', 'amPwdField');
        }
        if (!/[A-Za-z]/.test(pwd) || !/\d/.test(pwd)) {
            return fail('密码需要同时包含字母和数字', 'amPwdField');
        }
        if (pwd !== mask.querySelector('#amPwd2').value) {
            return fail('两次输入的密码不一致', 'amPwd2Field');
        }
    }
    // 验证码只有注册要填
    const needCode = mask.querySelector('#amCodeField').style.display !== 'none';
    const code = mask.querySelector('#amCode').value.replace(/\s/g, '');
    if (needCode) {
        if (!code) {
            return fail('请填写邮箱验证码', 'amCodeField');
        }
        if (code.length !== (Number(ME.emailCode.codeLength) || 6)) {
            return fail('验证码是 ' + (Number(ME.emailCode.codeLength) || 6) + ' 位数字', 'amCodeField');
        }
    }

    setAuthError('');
    markAuthField('');
    const btn = mask.querySelector('#amSubmit');
    const label = btn.textContent;
    btn.classList.add('loading');
    btn.innerHTML = '<span class="auth-spin"></span>' + (reg ? '注册中…' : '登录中…');
    try {
        // 注册成功后服务端直接签发令牌（注册用的验证码已作废，不该再让他收一条来登录）
        const r = reg
            ? await post('/api/auth/register', {username: user, password: pwd, code: code})
            : await post('/api/auth/login', {username: user, password: pwd});
        if (!r || !r.success) {
            // 验证码类的报错把焦点落到验证码框，别让用户对着密码框发呆
            const msg = (r && r.message) || (reg ? '注册失败，请稍后再试' : '登录失败，请稍后再试');
            return fail(msg, /验证码/.test(msg) ? 'amCodeField' : 'amUserField');
        }
        setAuthToken(r.data.token);
        ME.loggedIn = true;
        ME.user = r.data.user || null;
        renderNav();
        paintUserArea();
        const cb = _authCb;
        closeAuthModal(false);
        toast((reg ? '注册成功，' : '') + '登录成功', 'ok');
        if (cb.onSuccess) {
            cb.onSuccess(ME.user);
        }
    } finally {
        btn.classList.remove('loading');
        btn.textContent = label;
    }
}

/** 退出登录 */
async function doLogout() {
    try {
        await fetch('/api/auth/logout', {method: 'POST', headers: Object.assign({'Content-Type': 'application/json'}, authHeaders())});
    } catch (e) {
        // 退不退出得掉都要把本地令牌清掉
    }
    clearAuthToken();
    ME.loggedIn = false;
    ME.user = null;
    ME.impersonator = null;
    location.href = '/';
}

/**
 * 是否处在「超管代理登录某个用户」的状态。
 *
 * <p>判据取 {@code /api/auth/me} 回的 {@code impersonator}：只有服务端认下这个身份，
 * 顶栏才会挂出提示与退出入口，前端不自己造状态。
 */
function isImpersonating() {
    return !!(ME.loggedIn && ME.impersonator);
}

/**
 * 顶栏里显示的身份简称：邮箱只留 @ 前面那段。
 *
 * <p>顶栏是固定 56px 高的一行，全量邮箱（zhang.san@example.com）会把导航挤到第二行 ——
 * 而账号按钮本来就挨着提示，全名在那里有。
 */
function shortName(username) {
    const s = String(username == null ? '' : username);
    const at = s.indexOf('@');
    return at > 0 ? s.slice(0, at) : s;
}

/**
 * 退出代理登录：还原成代理者（超管）自己的登录态。
 *
 * <p>服务端把代理时的原令牌一并回带，这里换回本地 cookie 再整页刷新 ——
 * 页面上到处都可能是按用户隔离的数据，逐块重取不如重载一次干净。
 */
async function stopImpersonate() {
    const r = await post('/api/auth/impersonate/stop', {});
    if (!r || !r.success) {
        return;
    }
    const t = r.data && r.data.token;
    if (t) {
        setAuthToken(t);
    }
    ME.impersonator = null;
    toast(r.message || '已退出代理登录', 'ok');
    location.reload();
}

/** 请求后端接口，统一解析 {success,message,data} */
async function api(url, options) {
    const o = options || {};
    const headers = Object.assign({'Content-Type': 'application/json'}, o.headers || {});
    const at = authToken();
    if (at) {
        headers['X-Auth-Token'] = at;
    }
    try {
        const res = await fetch(url, Object.assign({}, o, {headers}));
        if (res.status === 401) {
            onUnauthorized();
            return {success: false, message: '请先登录', data: null};
        }
        const json = await res.json();
        if (!json.success) {
            toast(json.message || '请求失败', 'err');
        }
        return json;
    } catch (e) {
        toast('请求异常: ' + e.message, 'err');
        return {success: false, message: e.message, data: null};
    }
}

const get = (url) => api(url, {method: 'GET'});
const post = (url, body) => api(url, {method: 'POST', body: JSON.stringify(body || {})});
const put = (url, body) => api(url, {method: 'PUT', body: JSON.stringify(body || {})});
const del = (url) => api(url, {method: 'DELETE'});

/* ============ 股票名称跳转 ============ */
/**
 * 全站股票名称加超链接：点名称开新窗口跳转到「系统配置」页指定的平台。
 *
 * <p>「跳到哪」由后端 {@code /api/stock-link} 下发 —— URL 模板在 application.yml
 * （换域名改路径只动配置），「启用哪几个 / 默认点是哪个」由超管在「系统配置」页维护。
 * 下面这份默认值与 application.yml 的出厂值一致：接口还没回来时页面照样能渲染出
 * 可用的链接，等配置到了再由 {@link repaintStockLinks} 就地改写成实际配置，
 * 不会出现「先渲染成纯文本、之后永远不带链接」。
 *
 * <p>三个占位符（后端模板里写的）：{code} 6 位代码、{market} 交易所前缀、
 * {name} 名称。<b>{name} 替换时就地做 URL 编码</b>，模板里不用再套一层。
 */
const STOCK_LINK = {
    enabled: true,
    defaultTarget: 'chart',
    /** 是否已从服务端拿到过配置；拿不到就一直用下面这份内置默认 */
    loaded: false,
    targets: [
        {
            key: 'chart', label: '本系统 K 线查看', shortLabel: '本系统',
            url: '/pages/chart.html?code={code}', external: false, enabled: true
        },
        {
            key: 'baidu', label: '百度股市通', shortLabel: '百度',
            url: 'https://finance.baidu.com/stock/ab-{code}?name={name}', external: true, enabled: true
        },
        {
            key: 'xueqiu', label: '雪球', shortLabel: '雪球',
            url: 'https://xueqiu.com/S/{market}{code}', external: true, enabled: true
        }
    ]
};

/** 与 loadMode / loadMe 同样缓存 Promise：一页里几十行名称只拉一次配置 */
let _stockLinkPromise = null;
function loadStockLink() {
    if (!_stockLinkPromise) {
        _stockLinkPromise = (async function () {
            try {
                const res = await fetch('/api/stock-link');
                const json = await res.json();
                const d = (json && json.success) ? json.data : null;
                if (d && d.targets && d.targets.length) {
                    STOCK_LINK.enabled = d.enabled !== false;
                    STOCK_LINK.defaultTarget = d.defaultTarget || STOCK_LINK.defaultTarget;
                    STOCK_LINK.targets = d.targets;
                }
            } catch (e) {
                // 拉不到就用内置默认：链接照旧可用，只是可能不是超管最后配的那一份
            }
            STOCK_LINK.loaded = true;
            // 配置比首屏渲染晚到时，把已经渲染好的名称原地补成链接（页面不用重排）
            try {
                repaintStockLinks();
            } catch (e) {
                // 重绘失败不影响页面：链接已按默认配置渲染过了
            }
            return STOCK_LINK;
        })();
    }
    return _stockLinkPromise;
}

/**
 * 交易所前缀：6 / 9 开头是沪市（含 688 科创板），4 / 8 开头是北交所，其余按深市算。
 *
 * <p>雪球的路径要「市场前缀 + 代码」，而系统内部只存 6 位代码。
 * 指数（如 000001 上证指数）在这里会被判成深市 —— 同一个 6 位代码
 * 在两个交易所都成立时无法从代码本身区分，这是接口口径的固有歧义，
 * 不为了它去猜。
 */
function stockMarketOf(code) {
    const c = String(code == null ? '' : code).trim();
    const head = c.charAt(0);
    if (head === '6' || head === '9') return 'SH';
    if (head === '4' || head === '8') return 'BJ';
    return 'SZ';
}

/** 按模板拼出最终 URL；模板为空返回空串（调用方据此退化成纯文本） */
function stockUrlOf(target, code, name) {
    if (!target || !target.url) {
        return '';
    }
    const c = String(code == null ? '' : code).trim();
    const n = String(name == null || name === '' ? c : name);
    return String(target.url)
        .replace(/\{code\}/g, encodeURIComponent(c))
        .replace(/\{market\}/g, stockMarketOf(c))
        .replace(/\{name\}/g, encodeURIComponent(n));
}

/** 当前配置下可用的跳转目标 */
function stockTargetsOn() {
    const all = STOCK_LINK.targets || [];
    const on = [];
    for (let i = 0; i < all.length; i++) {
        const t = all[i];
        if (t && t.enabled !== false && t.url) {
            on.push(t);
        }
    }
    return on;
}

/** 目标链接的公共属性：一律新窗口，站外加 noopener noreferrer */
function stockAnchor(t, url, cls, inner, title) {
    return '<a class="' + cls + '" href="' + esc(url) + '" target="_blank"'
        + ' rel="' + (t.external ? 'noopener noreferrer' : 'noopener') + '"'
        + ' title="' + esc(title) + '">' + inner + '</a>';
}

function stockShortOf(t) {
    return t.shortLabel || t.label || t.key || '';
}

/** 名称链接 + 其余目标的小图标；配置不可用时返回空串（调用方退回纯文本） */
function stockRefInner(code, name, text, plain) {
    const c = String(code == null ? '' : code).trim();
    if (!c || !STOCK_LINK.enabled) {
        return '';
    }
    const targets = stockTargetsOn();
    if (!targets.length) {
        return '';
    }
    const label = esc(text == null || text === '' ? c : text);
    let def = null;
    for (let i = 0; i < targets.length; i++) {
        if (targets[i].key === STOCK_LINK.defaultTarget) {
            def = targets[i];
            break;
        }
    }
    if (!def) {
        def = targets[0];
    }
    const defUrl = stockUrlOf(def, c, name);
    if (!defUrl) {
        return '';
    }
    let html = stockAnchor(def, defUrl, 'sk-name', label,
        '跳转：' + (def.label || def.key) + '（新窗口）');
    if (!plain && targets.length > 1) {
        let icons = '';
        for (let i = 0; i < targets.length; i++) {
            const t = targets[i];
            if (t === def) {
                continue;
            }
            const u = stockUrlOf(t, c, name);
            if (!u) {
                continue;
            }
            icons += stockAnchor(t, u, 'sk-ico', esc(stockShortOf(t)),
                '跳转：' + (t.label || t.key) + '（新窗口）');
        }
        if (icons) {
            html += '<span class="sk-links">' + icons + '</span>';
        }
    }
    return html;
}

/**
 * 渲染一个「股票名称」：可点则输出带跳转的整块，不可点则退化成转义后的纯文本。
 *
 * <p>外层 {@code .stock-ref} 上带 code / name / 文本三个 data 属性，
 * 是为了配置晚到时能原地重绘（见 {@link repaintStockLinks}），页面不用重排。
 *
 * @param {string} code 6 位代码
 * @param {string} name 名称，可为空（为空时用代码当显示文本）
 * @param {object} [opt] {text} 指定显示文本（如「000001 上证指数」整体当链接文本）；
 *                       {plain:true} 只出名称链接、不出那排小图标（选择器 chip 这种窄容器用）
 */
function stockLinkHtml(code, name, opt) {
    const o = opt || {};
    const c = String(code == null ? '' : code).trim();
    const raw = (name == null || name === '') ? c : String(name);
    const text = (o.text == null || o.text === '') ? raw : String(o.text);
    const inner = stockRefInner(c, raw, text, !!o.plain);
    if (!inner) {
        return esc(text);
    }
    return '<span class="stock-ref' + (o.plain ? ' sk-plain' : '') + '" data-sk-code="' + esc(c)
        + '" data-sk-name="' + esc(raw) + '" data-sk-text="' + esc(text)
        + '" data-sk-plain="' + (o.plain ? '1' : '0') + '">' + inner + '</span>';
}

/** 配置到货后的原地重绘：只改 .stock-ref 里的内容，不动外层节点与其所在行 */
function repaintStockLinks() {
    const nodes = document.querySelectorAll('.stock-ref[data-sk-code]');
    for (let i = 0; i < nodes.length; i++) {
        const el = nodes[i];
        const inner = stockRefInner(el.getAttribute('data-sk-code'),
            el.getAttribute('data-sk-name'), el.getAttribute('data-sk-text'),
            el.getAttribute('data-sk-plain') === '1');
        if (inner && el.innerHTML !== inner) {
            el.innerHTML = inner;
        }
    }
}

/**
 * 配置一到手就重绘，所以这里在脚本解析阶段就把请求发出去 ——
 * 各页面的数据都要走一次接口才拿得到，这一份配置几乎总是先到。
 */
loadStockLink();

/* ============ 图标 ============ */
/** 提示条 / 弹窗共用的内联图标，避免为几个小图标引一整包字体或图标库 */
const ICON = {
    ok: '<svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="9"/><path d="M8.5 12.4l2.4 2.4 4.6-5"/></svg>',
    err: '<svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="9"/><path d="M12 7.5v5.2M12 16.3h.01"/></svg>',
    info: '<svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="9"/><path d="M12 11v5.5M12 7.7h.01"/></svg>',
    eye: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round"><path d="M2.2 12S5.8 5.6 12 5.6 21.8 12 21.8 12 18.2 18.4 12 18.4 2.2 12 2.2 12z"/><circle cx="12" cy="12" r="3.1"/></svg>',
    eyeOff: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round"><path d="M9.9 5.9A9.4 9.4 0 0 1 12 5.6c6.2 0 9.8 6.4 9.8 6.4a17 17 0 0 1-2.7 3.5M6.2 7.6A16.6 16.6 0 0 0 2.2 12S5.8 18.4 12 18.4c1.4 0 2.6-.3 3.7-.8"/><path d="M9.9 9.9a3.1 3.1 0 0 0 4.3 4.3M3 3l18 18"/></svg>',
    pwd: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round"><rect x="4.5" y="10.5" width="15" height="9.5" rx="2.5"/><path d="M8 10.5V7.8a4 4 0 0 1 8 0v2.7"/></svg>',
    out: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round"><path d="M15.5 8.5V6.2A2.2 2.2 0 0 0 13.3 4H6.7a2.2 2.2 0 0 0-2.2 2.2v11.6A2.2 2.2 0 0 0 6.7 20h6.6a2.2 2.2 0 0 0 2.2-2.2v-2.3"/><path d="M10 12h10M17.4 9.2L20 12l-2.6 2.8"/></svg>',
    spade: '<svg viewBox="0 0 24 24" fill="currentColor"><path d="M12 2.6c3.4 3.5 7.3 6.3 7.3 10.1 0 2.5-1.9 4.4-4.2 4.4-1 0-1.9-.3-2.6-.9.3 1.9 1.2 3.5 2.5 4.8H9c1.3-1.3 2.2-2.9 2.5-4.8-.7.6-1.6.9-2.6.9-2.3 0-4.2-1.9-4.2-4.4C4.7 8.9 8.6 6.1 12 2.6z"/></svg>',
    swap: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round"><path d="M4 8h13M14 4.8L17.2 8 14 11.2"/><path d="M20 16H7M10 12.8L6.8 16 10 19.2"/></svg>',
    github: '<svg viewBox="0 0 16 16" width="17" height="17" fill="currentColor" aria-hidden="true"><path d="M8 0C3.58 0 0 3.58 0 8c0 3.54 2.29 6.53 5.47 7.59.4.07.55-.17.55-.38 0-.19-.01-.82-.01-1.49-2.01.37-2.53-.49-2.69-.94-.09-.23-.48-.94-.82-1.13-.28-.15-.68-.52-.01-.53.63-.01 1.08.58 1.23.82.72 1.21 1.87.87 2.33.66.07-.52.28-.87.51-1.07-1.78-.2-3.64-.89-3.64-3.95 0-.87.31-1.59.82-2.15-.08-.2-.36-1.02.08-2.12 0 0 .67-.21 2.2.82.64-.18 1.32-.27 2-.27.68 0 1.36.09 2 .27 1.53-1.04 2.2-.82 2.2-.82.44 1.1.16 1.92.08 2.12.51.56.82 1.27.82 2.15 0 3.07-1.87 3.75-3.65 3.95.29.25.54.73.54 1.48 0 1.07-.01 1.93-.01 2.2 0 .21.15.46.55.38A8.01 8.01 0 0 0 16 8c0-4.42-3.58-8-8-8z"/></svg>'
};

/** 顶部提示条：类型决定图标与配色；文本一律转义后插入 */
function toast(message, type) {
    const box = document.getElementById('msgBox');
    if (!box) {
        alert(message);
        return;
    }
    const kind = type === 'err' ? 'err' : type === 'info' ? 'info' : 'ok';
    box.className = 'msg msg-' + kind;
    box.innerHTML = '<span class="msg-ico">' + ICON[kind] + '</span>'
        + '<span class="msg-txt">' + esc(message) + '</span>';
    box.style.display = 'flex';
    clearTimeout(box._timer);
    box._timer = setTimeout(() => {
        box.style.display = 'none';
    }, type === 'err' ? 8000 : 4000);
}

/* ============ 批量调用确认 ============ */
/** 预计调用次数超过这个值就弹二次确认（每一次请求都会真实触发行情数据服务调用，误点一次就是一批请求） */
const BULK_CONFIRM_OVER = 20;

/**
 * 批量更新行情前的二次确认。
 *
 * <p>行情数据服务日K接口单次请求支持任意长日期区间，所以<b>预计调用次数 = 股票只数</b>；
 * 预览模式同样会发出请求，因此两种模式都要确认。
 *
 * @param stockCount 本次要更新的股票只数，即预计接口调用次数
 * @param extra      追加提示（如筛选条件）
 * @returns {boolean} true 继续，false 放弃
 */
function confirmBulkFetch(stockCount, extra) {
    if (!stockCount || stockCount <= BULK_CONFIRM_OVER) {
        return true;
    }
    const lines = [
        '本次将更新 ' + stockCount + ' 只股票的行情',
        '预计发起 ' + stockCount + ' 次接口请求（每只股票整个日期区间只发一次请求）',
        '请求一旦发出就会真实触发行情数据服务调用（预览模式同样会发出请求）'
    ];
    if (extra) {
        lines.push(extra);
    }
    lines.push('', '确认继续？');
    return confirm(lines.join('\n'));
}

/** 数字格式化 */
function fmtNum(v, digits) {
    if (v === null || v === undefined || v === '') return '-';
    const n = Number(v);
    if (isNaN(n)) return v;
    const d = digits === undefined ? 2 : digits;
    return n.toLocaleString('zh-CN', {minimumFractionDigits: d, maximumFractionDigits: d});
}

/** 成交量按 手/万/亿 简写 */
function fmtVol(v) {
    if (v === null || v === undefined) return '-';
    const n = Number(v);
    if (isNaN(n)) return v;
    if (n >= 1e8) return (n / 1e8).toFixed(2) + '亿';
    if (n >= 1e4) return (n / 1e4).toFixed(2) + '万';
    return String(n);
}

/** 转义 HTML */
function esc(s) {
    if (s === null || s === undefined) return '';
    return String(s).replace(/[&<>"']/g, c => ({
        '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
    }[c]));
}

/** 本地日历日 yyyy-MM-dd（避免 toISOString 的 UTC 偏移） */
function fmtLocalDate(d) {
    const y = d.getFullYear();
    const m = String(d.getMonth() + 1).padStart(2, '0');
    const day = String(d.getDate()).padStart(2, '0');
    return y + '-' + m + '-' + day;
}

/** 今天 yyyy-MM-dd */
function today() {
    return fmtLocalDate(new Date());
}

/** N 天前的日期 */
function daysAgo(n) {
    const d = new Date();
    d.setDate(d.getDate() - n);
    return fmtLocalDate(d);
}

/** N 个月前的日期 */
function monthsAgo(n) {
    const d = new Date();
    d.setMonth(d.getMonth() - n);
    return fmtLocalDate(d);
}

/** 日期加减天数 */
function addDays(ymd, n) {
    const d = parseYmd(ymd);
    if (!d) {
        return '';
    }
    d.setDate(d.getDate() + n);
    return fmtLocalDate(d);
}

/** 解析 yyyy-MM-dd */
function parseYmd(s) {
    if (!s || !/^\d{4}-\d{2}-\d{2}$/.test(s)) {
        return null;
    }
    const p = s.split('-').map(Number);
    const d = new Date(p[0], p[1] - 1, p[2]);
    if (d.getFullYear() !== p[0] || d.getMonth() !== p[1] - 1 || d.getDate() !== p[2]) {
        return null;
    }
    return d;
}

/**
 * 宽松解析日期：支持 2024-01-05 / 2024/1/5 / 2024.01.05 / 20240105 / 24-01-05。
 * 匹配成功返回 yyyy-MM-dd，否则返回空串。
 */
function parseDateLoose(raw) {
    if (raw == null) {
        return '';
    }
    let s = String(raw).trim();
    if (!s) {
        return '';
    }
    s = s.replace(/年/g, '-').replace(/月/g, '-').replace(/日/g, '');
    let m = s.match(/^(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})$/);
    if (m) {
        return padYmd(+m[1], +m[2], +m[3]);
    }
    m = s.match(/^(\d{2})[-/.](\d{1,2})[-/.](\d{1,2})$/);
    if (m) {
        const yy = +m[1];
        return padYmd(yy >= 70 ? 1900 + yy : 2000 + yy, +m[2], +m[3]);
    }
    m = s.match(/^(\d{4})(\d{2})(\d{2})$/);
    if (m) {
        return padYmd(+m[1], +m[2], +m[3]);
    }
    return '';
}

function padYmd(y, mo, d) {
    const dt = new Date(y, mo - 1, d);
    if (dt.getFullYear() !== y || dt.getMonth() !== mo - 1 || dt.getDate() !== d) {
        return '';
    }
    return fmtLocalDate(dt);
}

/* ============ 主题：暗色 / 亮色 ============ */
const THEME_KEY = 'shadowflow_theme';
/** v1.6.0 之前的键名（品牌改名前的 {@code jn_} 前缀）；读到就迁到新键，别让用户手动选过的主题失效 */
const THEME_KEY_LEGACY = 'jn_theme';

/**
 * 读取已保存的主题（新键优先，旧键兜底并迁移）。
 *
 * <p>localStorage 在隐私模式下会直接抛异常，所以整段包 try。
 */
function readSavedTheme() {
    try {
        const saved = localStorage.getItem(THEME_KEY);
        if (saved === 'light' || saved === 'dark') {
            return saved;
        }
        const legacy = localStorage.getItem(THEME_KEY_LEGACY);
        if (legacy === 'light' || legacy === 'dark') {
            localStorage.setItem(THEME_KEY, legacy);
            localStorage.removeItem(THEME_KEY_LEGACY);
            return legacy;
        }
    } catch (e) {
        // 隐私模式下 localStorage 不可用，退回时间判断
    }
    return '';
}

/**
 * 已保存的主题；没手动选过就按客户端本地时间自动判断。
 *
 * <p>「自动」= 白天亮色 / 晚上暗色（见 {@link autoThemeByClock}），
 * 用户在顶栏点过太阳/月亮后以保存值为准，不再随时间跳变。
 */
function currentTheme() {
    const saved = readSavedTheme();
    return saved || autoThemeByClock();
}

/**
 * 按客户端本地时间自动选主题：06:00–18:00 亮色，其余暗色。
 *
 * <p>各页面 {@code <head>} 里的首屏内联脚本有一份等价实现 —— 那段必须早于 CSS 渲染执行，
 * 引不到本文件，改这里的规则（含键名）时记得同步改那十几份页面（否则会先按旧规则闪一下）。
 */
function autoThemeByClock() {
    const h = new Date().getHours();
    return h >= 6 && h < 18 ? 'light' : 'dark';
}

/** 落到 <html data-theme> 上，配色全部由 CSS 令牌接管 */
function applyTheme(theme) {
    const t = theme === 'light' ? 'light' : 'dark';
    document.documentElement.setAttribute('data-theme', t);
    const btn = document.getElementById('themeToggle');
    if (btn) {
        const tip = t === 'light' ? '切换到暗色' : '切换到亮色';
        btn.title = tip;
        btn.setAttribute('aria-label', tip);
    }
}

/** 切换并记住选择；存不下就只对本次会话生效 */
function toggleTheme() {
    const next = currentTheme() === 'light' ? 'dark' : 'light';
    try {
        localStorage.setItem(THEME_KEY, next);
        // 顺手清掉改名前的旧键，免得两处并存、以后改键值时互相干扰
        localStorage.removeItem(THEME_KEY_LEGACY);
    } catch (e) {
        // 忽略：写不进 cookie / localStorage 时页面仍然可用
    }
    applyTheme(next);
}

/** 顶栏右侧挂切换按钮：11 个页面共用，各页面不用自己写 */
function renderThemeToggle() {
    const bar = document.querySelector('.topbar');
    if (!bar || document.getElementById('themeToggle')) {
        return;
    }
    const sun = '<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor"'
        + ' stroke-width="2" stroke-linecap="round"><circle cx="12" cy="12" r="4.5"/>'
        + '<path d="M12 2v2M12 20v2M2 12h2M20 12h2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4'
        + 'M19.1 4.9l-1.4 1.4M6.3 17.7l-1.4 1.4"/></svg>';
    const moon = '<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor"'
        + ' stroke-width="2" stroke-linecap="round"><path d="M20.5 14.3A8.5 8.5 0 0 1 9.7 3.5'
        + 'a8.5 8.5 0 1 0 10.8 10.8z"/></svg>';
    const btn = document.createElement('button');
    btn.className = 'theme-toggle';
    btn.id = 'themeToggle';
    btn.type = 'button';
    btn.innerHTML = '<span class="ico-sun">' + sun + '</span><span class="ico-moon">' + moon + '</span>';
    btn.addEventListener('click', toggleTheme);
    bar.appendChild(btn);
}

/* ============ 系统 logo 与品牌 ============ */

/** 品牌名：logo 旁边那行字。改这里即可，全站同步（页面上没有第二份副本） */
const BRAND_NAME = '影流数据';

/**
 * 服务端当前的 logo 地址（{@code /img/logo.jpg} 或 {@code /api/logo/view?v=...}）。
 *
 * <p>初始值就是出厂 logo：接口没回来之前页面也能立刻把 logo 摆上，
 * 不会出现「先空一块、之后才补图」的跳动。
 */
let LOGO_URL = '/img/logo.jpg';

/** 拉一次 logo 地址。与 loadMenu 同样缓存 Promise —— 一次会话里只该问一次 */
let _logoPromise = null;
function loadLogo() {
    if (!_logoPromise) {
        _logoPromise = (async function () {
            try {
                const res = await fetch('/api/logo');
                const json = await res.json();
                const d = (json && json.success) ? json.data : null;
                if (d && d.url) {
                    LOGO_URL = d.url;
                }
            } catch (e) {
                // 拉不到就用出厂 logo
            }
            applyBrand();
            return LOGO_URL;
        })();
    }
    return _logoPromise;
}

/**
 * 把 logo + 品牌名挂到顶栏品牌位、登录弹窗标题上。
 *
 * <p>顶栏 HTML 里写的是纯文字品牌（「影流数据」）。这里把它整体换成
 * 「logo 图 + 文字」的结构；重绘是幂等的，logo 晚到（接口比首屏慢）时
 * 会被再调一次，把 <img> 的 src 换成新的。
 *
 * <p>登录弹窗是 JS 按需创建的，创建时也在品牌位插 logo（见 openAuthModal），
 * 所以这里只管已经存在于 DOM 里的那些，不去预先建弹窗。
 */
function applyBrand() {
    const bars = document.querySelectorAll('.topbar .brand');
    bars.forEach(el => {
        el.innerHTML = brandInner();
    });
    // 首页品牌带的大标题也挂 logo（与顶栏同一张图、同一份名称），
    // 换 logo 时首页跟着一起变 —— 只改 <img>，不重建 <h1>，免得把它的排版类丢掉
    const hero = document.getElementById('heroBrand');
    if (hero) {
        hero.innerHTML = '<img class="hero-logo" src="' + esc(LOGO_URL) + '" alt=""'
            + ' onerror="this.style.display=\'none\'">'
            + '<span>' + esc(BRAND_NAME) + '</span>';
    }
}

/** 品牌位的内容：logo 图 + 名称；logo 加载失败时自动隐藏图片，退回纯文字 */
function brandInner() {
    return '<img class="brand-logo" src="' + esc(LOGO_URL) + '" alt=""'
        + ' onerror="this.style.display=\'none\'">'
        + '<span class="brand-text">' + esc(BRAND_NAME) + '</span>';
}

/* ============ 导航 ============ */
/**
 * 全站菜单：两种运行模式共用同一份。
 *
 * <p>v1.3 起不再区分数据库 / 无数据库两套菜单 ——
 * 原先数据库模式下多出来的「数据更新」整组、「调用日志」「接口调试」全部移除：
 * 抓数落库已迁成别的入口，调用日志在无库模式按设计不落盘，接口调试本来就只在调试时用。
 * 新增页面只改这里，各页面不再各写一份导航。
 *
 * <p>{@code login: true} 的项点进去要登录（页面自己会跳登录页）；
 * {@code admin: true} 的项只有超管看得见。
 *
 * <p><b>v1.7.9 起超管的管理页收进「系统管理」一个分组</b>（{@code children} 是它的二级菜单）：
 * 普通用户的导航里没有这一项，自然也就看不到里面的任何一页。
 *
 * <p><b>v1.6.2 起「名称」与「顺序」可在「系统配置」页配置</b>（见 loadMenu / applyMenu）。
 * 这份数组仍是<b>唯一真相源</b>：{@code href} 与 {@code login} / {@code admin} 都只在这里 ——
 * 服务端只下发 key + 新名称 + 顺序，靠 {@code key} 回来索引本数组。
 * 所以配置里出现本数组没有的 key 一律忽略，本数组有而配置漏掉的按这里的顺序补回。
 * {@code key} 一旦定了就别改：它是配置行的落点，改了等于把用户配好的名称丢掉。
 * 二级项的归属（谁是哪个分组的）也由这里决定，配置只改名称与组内顺序。
 */
const NAV_ITEMS = [
    {key: 'home', text: '概览', href: '/'},
    {key: 'stock-list', text: '股票列表', href: '/pages/stock-list.html'},
    {key: 'chart', text: 'K线查看', href: '/pages/chart.html'},
    {key: 'news', text: '实时资讯', href: '/pages/news.html'},
    {key: 'backtest', text: '策略回测', href: '/pages/backtest.html', login: true},
    {key: 'watch', text: '盯盘', href: '/pages/watch.html', login: true},
    {key: 'account', text: '我的账户', href: '/pages/account.html', login: true},
    {key: 'feedback', text: '问题反馈', href: '/pages/feedback.html'},
    {
        key: 'admin', text: '系统管理', admin: true, children: [
            {key: 'system', text: '系统配置', href: '/pages/system.html'},
            {key: 'push', text: '钉钉推送', href: '/pages/push.html'},
            {key: 'users', text: '用户管理', href: '/pages/users.html'},
            // 超管按用户查看账户与持仓（只读，卡片展示），不用代理登录
            {key: 'admin-accounts', text: '账户管理', href: '/pages/admin-accounts.html'},
            {key: 'migrate', text: '数据迁移', href: '/pages/migrate.html'}
        ]
    }
];

/**
 * 服务端下发的菜单配置：{customized, items:[{key,text,href}]}。
 *
 * <p>{@code null} 表示还没拿到（或拿不到）—— {@link renderNav} 直接按 {@link NAV_ITEMS} 的内置
 * 名称与顺序渲染，所以接口挂了页面也照常可用，只是名称回到出厂值。
 */
let MENU_CFG = null;

/**
 * 拉一次菜单配置。与 loadMode / loadMe 同样缓存 Promise：一页里导航只会渲染一次，
 * 但页面脚本可能也会去读，缓存住免得发两遍请求、两遍结果还可能一前一后。
 */
let _menuPromise = null;
function loadMenu() {
    if (!_menuPromise) {
        _menuPromise = (async function () {
            try {
                const res = await fetch('/api/menu');
                const json = await res.json();
                const d = (json && json.success) ? json.data : null;
                if (d && d.items && d.items.length) {
                    MENU_CFG = d;
                }
            } catch (e) {
                // 拉不到就按内置名称与顺序渲染
            }
            return MENU_CFG;
        })();
    }
    return _menuPromise;
}

/** 内置菜单的扁平索引：key → 项。二级项带 parent（所属分组的 key），渲染与配置都按 key 回索引 */
function builtInCatalog() {
    const map = {};
    NAV_ITEMS.forEach(it => {
        map[it.key] = {
            key: it.key, text: it.text, href: it.href,
            login: it.login, admin: it.admin, children: it.children
        };
        (it.children || []).forEach(c => {
            map[c.key] = {
                key: c.key, text: c.text, href: c.href,
                login: c.login, admin: c.admin, parent: it.key
            };
        });
    });
    return map;
}

/**
 * 把内置菜单与下发的配置合成「最终要渲染的那一份」。
 *
 * <p>做法是<b>以配置的 items 顺序为主、按 key 回索引内置项</b>：
 * 内置里有而配置里没有的（新增了菜单但没重新配过），按内置顺序补到队尾。
 * 这样加一个新页面时老配置不会把它挤掉。
 *
 * <p>分组（有 children 的项）的子项顺序同样按配置走，配不到的按内置顺序补齐；
 * 二级项不允许单独出现在顶层 —— 它的位置只由所属分组决定。
 *
 * @returns {Array} [{key, text, href, login, admin, children?}]，text 已按配置覆盖
 */
function menuItems() {
    const cfg = MENU_CFG;
    if (!cfg || !cfg.items || !cfg.items.length) {
        return NAV_ITEMS.slice();
    }
    const cat = builtInCatalog();
    const used = {};
    const out = [];
    cfg.items.forEach(c => {
        const base = c && c.key ? cat[c.key] : null;
        if (!base || used[base.key] || base.parent) {
            return;
        }
        used[base.key] = 1;
        const item = Object.assign({}, base, {text: (c.text || base.text)});
        if (base.children) {
            const kids = [];
            (c.children || []).forEach(k => {
                const kb = k && k.key ? cat[k.key] : null;
                if (!kb || !kb.parent || used[kb.key]) {
                    return;
                }
                used[kb.key] = 1;
                kids.push(Object.assign({}, kb, {text: (k.text || kb.text)}));
            });
            // 配置里缺的二级项按内置顺序补回（老后端不带 children 时全靠这一段）
            base.children.forEach(cb => {
                if (used[cb.key]) {
                    return;
                }
                used[cb.key] = 1;
                kids.push(cat[cb.key]);
            });
            item.children = kids;
        }
        out.push(item);
    });
    // 配置里没有的（含内置新加的）按内置顺序补回队尾
    NAV_ITEMS.forEach(it => {
        if (!used[it.key]) {
            out.push(it);
        }
    });
    return out;
}

/**
 * 全部叶子菜单：顶层的平铺项 + 分组的二级项。
 *
 * <p>首页「功能入口」这种一维列表用它 —— 分组本身没有地址，不能点。
 */
function menuLeaves() {
    const out = [];
    menuItems().forEach(m => {
        if (m.children && m.children.length) {
            m.children.forEach(c => out.push(c));
        } else {
            out.push(m);
        }
    });
    return out;
}

/** 当前身份下该菜单项是否可见（分组的可见性看分组自己，二级项跟着分组走） */
function navVisible(item) {
    return !item.admin || isAdmin();
}

/* =====================================================================
 * 菜单配置（v1.6.2）
 *
 * 「系统配置」页的「菜单配置」按钮 + 弹窗改的就是这里：名称与顺序可配，
 * 地址与权限不给配。默认值 = NAV_ITEMS（当前数据），所以第一次打开弹窗
 * 看到的就是现在页面上这一套。
 *
 * 可配的只有这两样是刻意的：地址可配等于开一堆 404 死菜单，
 * 权限可配等于开越权入口 —— 这两件事代码说了算，配置说了不算。
 * ===================================================================== */

/**
 * 菜单配置弹窗的 HTML。
 *
 * <p>与站内其它弹窗同一套结构：{@code .mask + .modal + .modal-actions}。
 * 行内用 index 作 data 标识 —— 弹窗里的这一份是「待保存的草稿」，不是最终菜单，
 * 靠 key 定位反而会在重排后错位。
 */
function menuModalHtml() {
    return '<div class="mask" id="menuMask">'
        + '<div class="modal modal-lg" role="dialog" aria-modal="true" aria-label="菜单配置">'
        + '<h3>菜单配置</h3>'
        + '<div class="dim" style="font-size:var(--fs-12);margin-bottom:14px">'
        + '改的是<b>名称</b>与<b>顺序</b>。地址、登录要求与超管权限由代码决定，这里配不到 ——'
        + '避免配出一个点进去 404 的死菜单，或把超管入口暴露给所有人。'
        + '「系统管理」下的二级菜单同样能改名，组内顺序跟着所属分组走。'
        + '</div>'
        + '<div class="menu-rows" id="menuRows"></div>'
        + '<div class="modal-actions">'
        + '<button class="btn-ghost" onclick="resetMenuConfig()">恢复默认</button>'
        + '<button class="btn-ghost" onclick="closeMenuModal()">取消</button>'
        + '<button id="menuSave">保存</button>'
        + '</div>'
        + '</div></div>';
}

/** 菜单配置弹窗里的编辑态：一份 {key,text,href,level,admin,parent} 的草稿数组（按渲染顺序扁平存放） */
let menuDraft = [];

async function openMenuModal() {
    const r = await get('/api/menu');
    if (!r || !r.success) {
        return;
    }
    // 草稿必须含「全部菜单项」，不能只用服务端下发的 items。
    // 服务端会把自定义顺序里没出现的 key 补在队尾并下发，但一旦下发被裁剪
    // （老后端 / 代理改包 / 字段缺失），只用 items 就会让超管看不到、也配不了那些项。
    // 这里以「内置 NAV_ITEMS 为全集」做合并：配置给名称与顺序，内置补齐缺的。
    const cfgItems = (r.data && r.data.items) || [];
    const cat = builtInCatalog();
    const draft = [];
    const used = {};
    const pushTop = (key, text) => {
        const base = cat[key];
        if (!base || used[key]) {
            return;
        }
        used[key] = 1;
        draft.push({
            key: key, text: text || base.text, href: base.href,
            level: 0, admin: !!base.admin
        });
        // 二级项紧跟在分组后面：组内顺序由内置数组决定，这里不给配
        (base.children || []).forEach(cb => {
            if (used[cb.key]) {
                return;
            }
            used[cb.key] = 1;
            draft.push({
                key: cb.key, text: cb.text, href: cb.href,
                level: 1, admin: !!base.admin, parent: key
            });
        });
    };
    cfgItems.forEach(c => {
        const base = c && c.key ? cat[c.key] : null;
        if (!base || base.parent) {
            return;
        }
        pushTop(base.key, c.text);
        // 配置里带了二级名称的，按配置覆盖（顺序仍按内置）
        (c.children || []).forEach(k => {
            const row = k && k.key ? draft.find(x => x.key === k.key) : null;
            if (row && k.text) {
                row.text = k.text;
            }
        });
    });
    NAV_ITEMS.forEach(it => pushTop(it.key, it.text));
    menuDraft = draft;
    let mask = document.getElementById('menuMask');
    if (!mask) {
        const wrap = document.createElement('div');
        wrap.innerHTML = menuModalHtml();
        mask = wrap.firstChild;
        document.body.appendChild(mask);
        mask.addEventListener('mousedown', e => {
            if (e.target === mask) {
                closeMenuModal();
            }
        });
        // 事件委托：行是重排后重建的，逐个绑太脆
        mask.addEventListener('click', e => {
            const btn = e.target.closest('button[data-act]');
            if (!btn) {
                return;
            }
            const row = btn.closest('.menu-row');
            const i = Number(row.getAttribute('data-i'));
            const act = btn.getAttribute('data-act');
            if (act !== 'up' && act !== 'down') {
                return;
            }
            if (!moveMenuRow(i, act === 'up' ? -1 : 1)) {
                return;
            }
            paintMenuRows();
        });
        mask.addEventListener('input', e => {
            if (e.target.classList.contains('menu-text')) {
                const row = e.target.closest('.menu-row');
                menuDraft[Number(row.getAttribute('data-i'))].text = e.target.value;
            }
        });
        mask.querySelector('#menuSave').addEventListener('click', saveMenuConfig);
    }
    paintMenuRows();
    mask.classList.add('show');
}

/**
 * 上移 / 下移一条顶层菜单。
 *
 * <p>2 级项不参与排序（组内顺序由代码决定），所以这里只处理 level = 0 的行；
 * 移动时<b>连同它下面的二级行整块搬</b>，否则分组会和自己的子项分家。
 *
 * @returns {boolean} 是否真的动了（没动就不必重绘）
 */
function moveMenuRow(i, step) {
    const row = menuDraft[i];
    if (!row || row.level !== 0) {
        return false;
    }
    // 一块 = 这一行 + 紧跟其后、属于它的二级行
    const blockOf = start => {
        let n = 1;
        while (start + n < menuDraft.length && menuDraft[start + n].level === 1) {
            n++;
        }
        return n;
    };
    if (step < 0) {
        // 往上找最近的一条顶层行（二级行一律跳过）
        let j = i - 1;
        while (j >= 0 && menuDraft[j].level !== 0) {
            j--;
        }
        if (j < 0) {
            return false;
        }
        const block = menuDraft.splice(i, blockOf(i));
        menuDraft.splice(j, 0, ...block);
        return true;
    }
    const block = menuDraft.splice(i, blockOf(i));
    // 取出本块后，紧跟其后的那块从 i 开始；后面没有东西就说明已经在队尾
    if (i >= menuDraft.length) {
        menuDraft.splice(i, 0, ...block);
        return false;
    }
    menuDraft.splice(i + blockOf(i), 0, ...block);
    return true;
}

function closeMenuModal() {
    const mask = document.getElementById('menuMask');
    if (mask) {
        mask.classList.remove('show');
    }
}

function paintMenuRows() {
    const box = document.getElementById('menuRows');
    if (!box) {
        return;
    }
    let topSeq = 0;
    box.innerHTML = menuDraft.map((it, i) => {
        const sub = it.level === 1;
        const tag = it.admin ? '<span class="sl-tag">超管</span>' : '';
        // 二级行不排序，也没有地址行（地址看所属分组），只留名称可改
        const hasPrevTop = menuDraft.slice(0, i).some(x => x.level === 0);
        const hasNextTop = menuDraft.slice(i + 1).some(x => x.level === 0);
        const ops = sub
            ? '<span class="menu-ops"></span>'
            : '<span class="menu-ops">'
              + '<button type="button" class="btn-sm btn-ghost" data-act="up"'
              + (hasPrevTop ? '' : ' disabled') + ' aria-label="上移">↑</button>'
              + '<button type="button" class="btn-sm btn-ghost" data-act="down"'
              + (hasNextTop ? '' : ' disabled')
              + ' aria-label="下移">↓</button>'
              + '</span>';
        if (!sub) {
            topSeq++;
        }
        return '<div class="menu-row' + (sub ? ' menu-row-sub' : '') + '" data-i="' + i + '">'
            + '<span class="menu-seq">' + (sub ? '·' : topSeq) + '</span>'
            + '<input class="menu-text" maxlength="12" value="' + esc(it.text) + '"'
            + ' aria-label="菜单名称" spellcheck="false">'
            + '<span class="menu-href mono">'
            + (sub ? '' : esc(it.href || '（二级菜单）'))
            + '</span>'
            + tag
            + ops
            + '</div>';
    }).join('');
}

async function saveMenuConfig() {
    const items = menuDraft.map(it => ({key: it.key, text: (it.text || '').trim()}));
    const empty = items.filter(it => !it.text);
    if (empty.length) {
        return toast('菜单名称不能为空', 'err');
    }
    const btn = document.getElementById('menuSave');
    btn.disabled = true;
    let r;
    try {
        r = await post('/api/admin/menu', {items: items});
    } finally {
        btn.disabled = false;
    }
    if (!r || !r.success) {
        return;
    }
    // 保存成功 = 配置已生效，但本页导航还是旧的：重绘一次即可，不用刷新页面
    applyMenuConfig(r.data);
    closeMenuModal();
    toast(r.message || '菜单配置已保存并生效', 'ok');
}

async function resetMenuConfig() {
    if (!confirm('恢复默认后，菜单名称与顺序回到内置的那一套，确认？')) {
        return;
    }
    const r = await post('/api/admin/menu/reset', {});
    if (!r || !r.success) {
        return;
    }
    applyMenuConfig(r.data);
    closeMenuModal();
    toast(r.message || '已恢复默认菜单', 'ok');
}

/**
 * 把一份新的菜单配置应用到当前页面。
 *
 * <p>MENU_CFG 换掉之后重绘导航与首页功能入口 —— 保存完不刷新页面就能看到效果，
 * 不然用户会以为没生效、又点一次。
 */
function applyMenuConfig(data) {
    MENU_CFG = (data && data.items && data.items.length) ? data : null;
    // 把已缓存的 Promise 也换掉，免得同一会话里其它页面脚本再拿到旧的那份
    _menuPromise = Promise.resolve(MENU_CFG);
    renderNav();
    if (typeof renderQuick === 'function') {
        renderQuick();
    }
}

/* =====================================================================
 * 系统 logo 上传（v1.6.2）
 * ===================================================================== */

/** 打开系统配置页的 logo 上传入口：选文件 → 上传 → 顶栏立刻换图 */
function pickLogoFile() {
    let input = document.getElementById('logoFile');
    if (!input) {
        input = document.createElement('input');
        input.type = 'file';
        input.id = 'logoFile';
        input.accept = 'image/jpeg,image/png,image/webp,image/gif';
        input.style.display = 'none';
        input.addEventListener('change', () => {
            const f = input.files && input.files[0];
            input.value = '';
            if (f) {
                uploadLogo(f);
            }
        });
        document.body.appendChild(input);
    }
    input.click();
}

async function uploadLogo(file) {
    const fd = new FormData();
    fd.append('file', file);
    let r;
    try {
        const res = await fetch('/api/admin/logo/upload', {method: 'POST', body: fd});
        r = await res.json();
    } catch (e) {
        return toast('上传失败，请重试', 'err');
    }
    if (!r || !r.success) {
        return;
    }
    // 换掉地址（服务端带了 ?v= 时间戳，浏览器会立刻拉新图，不吃 7 天缓存）
    if (r.data && r.data.url) {
        LOGO_URL = r.data.url;
    }
    _logoPromise = Promise.resolve(LOGO_URL);
    applyBrand();
    toast(r.message || 'logo 已更新', 'ok');
    if (typeof onLogoChanged === 'function') {
        onLogoChanged();
    }
}

async function resetLogo() {
    if (!confirm('恢复默认后，系统 logo 回到出厂的那张图，确认？')) {
        return;
    }
    const r = await post('/api/admin/logo/reset', {});
    if (!r || !r.success) {
        return;
    }
    if (r.data && r.data.url) {
        LOGO_URL = r.data.url;
    }
    _logoPromise = Promise.resolve(LOGO_URL);
    applyBrand();
    toast(r.message || '已恢复默认 logo', 'ok');
    if (typeof onLogoChanged === 'function') {
        onLogoChanged();
    }
}


/** 渲染导航并高亮当前页面 */
function renderNav() {
    const nav = document.getElementById('nav');
    if (!nav) {
        return;
    }
    const path = location.pathname === '/index.html' ? '/' : location.pathname;
    nav.innerHTML = menuItems().filter(navVisible).map(item => navLink(item, path)).join('');
    // 未登录点「策略回测」「盯盘」这类页面：先弹登录框，登上了再放行，不用先跳空页面
    nav.querySelectorAll('a[data-login]').forEach(a => {
        a.addEventListener('click', e => {
            if (isLogin()) {
                return;
            }
            e.preventDefault();
            const href = a.getAttribute('href');
            openAuthModal({mode: 'login', onSuccess: () => { location.href = href; }});
        });
    });
    // 分组：桌面靠 :hover 展开，这里补一条点击切换 —— 触屏没有 hover，
    // 窄屏导航又是横向滚动（overflow 会把绝对定位的下拉裁掉，见 common.css 的 720 断点）
    nav.querySelectorAll('.nav-group > .nav-item').forEach(toggle => {
        toggle.addEventListener('click', () => {
            const group = toggle.parentNode;
            const open = !group.classList.contains('open');
            nav.querySelectorAll('.nav-group.open').forEach(g => g.classList.remove('open'));
            group.classList.toggle('open', open);
        });
    });
}

function navLink(item, path, sub) {
    // 分组：本身没有地址，悬停 / 聚焦展开二级菜单（.nav-group 的样式在 common.css 里）
    if (item.children && item.children.length) {
        const kids = item.children.filter(navVisible)
            .map(c => navLink(c, path, true)).join('');
        if (!kids) {
            return '';
        }
        const active = item.children.some(c => c.href === path) ? ' active' : '';
        return `<div class="nav-group${active}">`
            + `<span class="nav-item" tabindex="0" aria-haspopup="true">${esc(item.text)}`
            + '<i class="caret">▾</i></span>'
            + `<div class="nav-sub">${kids}</div>`
            + '</div>';
    }
    const flag = item.login && !isLogin() ? ' data-login="1"' : '';
    // 名称可能来自系统配置页（用户自己填的），一律转义后再拼 —— 否则一个 < 就能把导航拆了
    return `<a class="${sub ? 'nav-sub-item' : 'nav-item'}${path === item.href ? ' active' : ''}"`
        + ` href="${esc(item.href)}"${flag}>${esc(item.text)}</a>`;
}

/* ============ 顶栏账号区 ============ */
/** 顶栏右侧：未登录显示「登录 / 注册」，已登录显示邮箱与下拉菜单 */
function renderUserArea() {
    const bar = document.querySelector('.topbar');
    if (!bar || document.getElementById('userArea')) {
        return;
    }
    const box = document.createElement('div');
    box.className = 'user-area';
    box.id = 'userArea';
    bar.appendChild(box);
    paintUserArea();
}

/* ============ 全站页脚 ============ */

/** 页脚署名。改这里即可，全站 14 个页面同步（页脚是注入的，页面里没有副本） */
const SITE_AUTHOR = 'zhoutaoqiang';

/** 项目开源地址，页脚挂入口用 */
const SITE_REPO = 'https://github.com/zhoutaoq/shadow-flow-astock-data';

/**
 * 注入页脚：署名 + 版本号 + 发布时间。
 *
 * <p>版本号与发布时间来自 {@code /api/version} 而不是写死在前端 —— 打包时
 * Maven 会把 pom 的 version 与构建时间写进 {@code build-info.properties}，
 * 所以升级版本只需改 pom，页脚自动跟着变，不会再出现「页面写着 1.3.6、
 * 实际跑的是 1.3.7」这种对不上的情况。
 *
 * <p>接口拿不到时（老版本后端、网络异常、IDE 直跑未过滤资源）退化成只显示
 * 署名与一个占位版本，绝不把 undefined / null 打到页面上。
 */
async function renderFooter() {
    if (document.querySelector('.site-footer')) {
        return;
    }
    const foot = document.createElement('footer');
    foot.className = 'site-footer';
    foot.id = 'siteFooter';
    foot.innerHTML = '<div class="site-footer-inner">'
        + '<span>Powered by <span class="sf-author">' + esc(SITE_AUTHOR) + '</span></span>'
        + '<i class="sf-sep"></i>'
        + '<a class="sf-link" href="' + esc(SITE_REPO) + '" target="_blank" rel="noopener noreferrer"'
        + ' title="项目开源地址">' + ICON.github + '<span>GitHub</span></a>'
        + '<i class="sf-sep"></i>'
        + '<span class="sf-ver" id="sfVer">v—</span>'
        + '<i class="sf-sep"></i>'
        + '<span id="sfTime">发布时间 —</span>'
        + '</div>';
    document.body.appendChild(foot);

    let version = '';
    let buildTime = '';
    try {
        const r = await get('/api/version');
        if (r && r.success && r.data) {
            version = r.data.version || '';
            buildTime = r.data.buildTime || '';
        }
    } catch (e) {
        // 拿不到就留空，下面按占位显示
    }
    const verEl = document.getElementById('sfVer');
    const timeEl = document.getElementById('sfTime');
    if (verEl) {
        verEl.textContent = 'v' + (version || '—');
    }
    if (timeEl) {
        timeEl.textContent = '发布于 ' + (buildTime || '—');
    }
}

function paintUserArea() {
    const box = document.getElementById('userArea');
    if (!box) {
        return;
    }
    if (!ME.loggedIn) {
        box.innerHTML = '<a class="ua-link" href="javascript:void(0)" id="uaLogin">登录</a>'
            + '<a class="ua-link" href="javascript:void(0)" id="uaRegister">注册</a>';
        box.querySelector('#uaLogin').addEventListener('click', () => openAuthModal({mode: 'login'}));
        box.querySelector('#uaRegister').addEventListener('click', () => openAuthModal({mode: 'register'}));
        return;
    }
    const u = ME.user || {};
    // 头像取邮箱首字母，admin 就是 a，一眼能认出是哪个号
    const initial = (u.username || '?').trim().charAt(0);
    // 代理登录时先在账号区前面挂一枚提示，随顶栏常驻，随时能退出来
    const imp = isImpersonating()
        ? '<span class="imp-chip" id="impChip">' + ICON.swap
            + '<span class="imp-txt">已切换到 <span class="imp-who">'
            + esc(shortName(u.username)) + '</span></span>'
            + '<button type="button" id="impStop">退出</button></span>'
        : '';
    box.innerHTML = imp + '<div class="ua-group">'
        + '<button class="ua-btn" type="button" aria-haspopup="menu">'
        + '<span class="ua-avatar">' + esc(initial) + '</span>'
        + '<span class="ua-name">' + esc(u.username) + '</span>'
        + (u.admin ? '<span class="ua-role">超管</span>' : '')
        + '<i class="ua-caret">▾</i></button>'
        + '<div class="ua-menu" role="menu">'
        + '<a href="javascript:void(0)" id="uaPwd" role="menuitem">' + ICON.pwd + '修改密码</a>'
        + (isImpersonating()
            ? '<a href="javascript:void(0)" id="uaImpStop" role="menuitem">' + ICON.swap + '退出代理登录</a>'
            : '')
        + '<a href="javascript:void(0)" id="uaLogout" class="danger" role="menuitem">' + ICON.out + '退出登录</a>'
        + '</div></div>';
    box.querySelector('#uaPwd').addEventListener('click', openPasswordModal);
    box.querySelector('#uaLogout').addEventListener('click', doLogout);
    const stopBtn = box.querySelector('#impStop');
    if (stopBtn) {
        stopBtn.addEventListener('click', stopImpersonate);
    }
    const stopItem = box.querySelector('#uaImpStop');
    if (stopItem) {
        stopItem.addEventListener('click', stopImpersonate);
    }
}

/**
 * 修改自己的密码：必须验证原密码，改完服务端会把登录态作废，这里直接回登录页。
 *
 * <p>密码规则与后端 {@code AuthService.checkPassword} 一致：
 * 不少于 6 位且同时含字母和数字；这里先校验一遍，省得白跑一次请求。
 */
function openPasswordModal() {
    let mask = document.getElementById('pwdMask');
    if (!mask) {
        mask = document.createElement('div');
        mask.className = 'mask';
        mask.id = 'pwdMask';
        mask.innerHTML = '<div class="modal modal-auth" role="dialog" aria-modal="true" aria-label="修改密码">'
            + '<div class="auth-head">'
            + '<span class="auth-brand"><span class="mark">' + ICON.pwd + '</span>修改密码</span>'
            + '</div>'
            + '<div class="auth-alert" id="pwdErr">' + ICON.err + '<span id="pwdErrTxt"></span></div>'
            + '<div class="auth-field" id="pwdOldField"><label for="pwdOld">当前密码</label>'
            + '<div class="auth-input-wrap has-toggle">'
            + '<input type="password" id="pwdOld" autocomplete="current-password">'
            + '<button class="auth-eye" type="button" data-for="pwdOld" aria-label="显示密码">' + ICON.eye + '</button>'
            + '</div></div>'
            + '<div class="auth-field" id="pwdNewField"><label for="pwdNew">新密码</label>'
            + '<div class="auth-input-wrap has-toggle">'
            + '<input type="password" id="pwdNew" autocomplete="new-password">'
            + '<button class="auth-eye" type="button" data-for="pwdNew" aria-label="显示密码">' + ICON.eye + '</button>'
            + '</div></div>'
            + '<div class="auth-field" id="pwdNew2Field"><label for="pwdNew2">确认新密码</label>'
            + '<div class="auth-input-wrap has-toggle">'
            + '<input type="password" id="pwdNew2" autocomplete="new-password">'
            + '<button class="auth-eye" type="button" data-for="pwdNew2" aria-label="显示密码">' + ICON.eye + '</button>'
            + '</div></div>'
            + '<button class="auth-submit" id="pwdSave" type="button">保存新密码</button>'
            + '</div>';
        document.body.appendChild(mask);
        const hide = () => mask.classList.remove('show');
        mask.querySelectorAll('.auth-eye').forEach(btn => {
            btn.addEventListener('click', () => {
                const input = mask.querySelector('#' + btn.getAttribute('data-for'));
                const show = input.type === 'password';
                input.type = show ? 'text' : 'password';
                btn.innerHTML = show ? ICON.eyeOff : ICON.eye;
            });
        });
        mask.addEventListener('mousedown', e => {
            if (e.target === mask) hide();
        });
        mask.querySelector('#pwdSave').addEventListener('click', () => submitChangePassword(hide));
        ['pwdOld', 'pwdNew', 'pwdNew2'].forEach(id => {
            mask.querySelector('#' + id).addEventListener('keydown', e => {
                if (e.key === 'Enter') {
                    e.preventDefault();
                    submitChangePassword(hide);
                }
            });
        });
    }
    ['pwdOld', 'pwdNew', 'pwdNew2'].forEach(id => {
        const el = mask.querySelector('#' + id);
        el.value = '';
        el.type = 'password';
    });
    mask.querySelectorAll('.auth-eye').forEach(b => { b.innerHTML = ICON.eye; });
    setPwdError('');
    mask.classList.add('show');
    setTimeout(() => mask.querySelector('#pwdOld').focus(), 60);
}

/** 改密弹窗内的错误提示 */
function setPwdError(text) {
    const mask = document.getElementById('pwdMask');
    if (!mask) {
        return;
    }
    mask.querySelector('#pwdErrTxt').textContent = text || '';
    mask.querySelector('#pwdErr').classList.toggle('show', !!text);
}

async function submitChangePassword(hide) {
    const mask = document.getElementById('pwdMask');
    const oldPwd = mask.querySelector('#pwdOld').value;
    const newPwd = mask.querySelector('#pwdNew').value.trim();
    const newPwd2 = mask.querySelector('#pwdNew2').value.trim();
    const min = (ME.rules && ME.rules.minPasswordLength) || 6;
    if (!oldPwd) {
        return setPwdError('请填写当前密码');
    }
    if (newPwd.length < min) {
        return setPwdError('新密码至少 ' + min + ' 位');
    }
    if (!/[A-Za-z]/.test(newPwd) || !/\d/.test(newPwd)) {
        return setPwdError('新密码需要同时包含字母和数字');
    }
    if (newPwd !== newPwd2) {
        return setPwdError('两次输入的新密码不一致');
    }
    setPwdError('');
    const btn = mask.querySelector('#pwdSave');
    btn.classList.add('loading');
    btn.innerHTML = '<span class="auth-spin"></span>保存中…';
    try {
        const r = await put('/api/auth/password', {oldPassword: oldPwd, newPassword: newPwd});
        if (!r || !r.success) {
            return setPwdError((r && r.message) || '修改失败，请稍后再试');
        }
        hide();
        clearAuthToken();
        ME.loggedIn = false;
        ME.user = null;
        renderNav();
        paintUserArea();
        toast('密码已修改，请用新密码重新登录', 'ok');
        setTimeout(() => openAuthModal({mode: 'login'}), 600);
    } finally {
        btn.classList.remove('loading');
        btn.textContent = '保存新密码';
    }
}

/* =====================================================================
 * 窄屏表格卡片化：把表头列名写进每个单元格（v1.5.9 移动端适配）
 *
 * 为什么要这一手：窄屏下 width:100% 的宽表格只能横向拖，十几列的表拖起来
 * 根本对不上号。CSS 能把 <tr> 显示成卡片（见 common.css 的 720px 断点），
 * 但 CSS 读不到表头文字，标不出「这一格是什么」—— 只能由 JS 在这里补 data-label，
 * CSS 再用 ::before 渲染到值的左边。
 *
 * 选在 JS 里统一做、而不是去改十几处表格的渲染代码：各页表格有的写在 HTML 里、
 * 有的是拼字符串生成的（股票列表、实时资讯、回测明细、调用日志），逐个改既容易漏，
 * 下次加一列还会再漏一次。这里按「表头第 N 列 → 第 N 格」的位置对应来标，
 * 任何表格渲染完都能自动跟上。
 *
 * 覆盖两类宽表：主表格 table.grid，以及账户页持仓表 table.atable（12 列，
 * 只靠 .atable-wrap 横向滚动，窄屏拖起来同样对不上号，一并卡片化）。
 * ===================================================================== */
function labelGridTables(root) {
    const scope = root || document;
    if (!scope.querySelectorAll) {
        return;
    }
    scope.querySelectorAll('table.grid, table.atable').forEach(function (t) {
        const heads = [];
        t.querySelectorAll('thead th').forEach(function (th) {
            heads.push((th.textContent || '').trim());
        });
        // 没有表头就无从知道列名，保持原样（窄屏下退化成可横向拖动的普通表格）
        if (!heads.length) {
            return;
        }
        t.querySelectorAll('tbody tr').forEach(function (tr) {
            const cells = tr.children;
            for (let i = 0; i < cells.length; i++) {
                const td = cells[i];
                if (td.tagName !== 'TD') {
                    continue;
                }
                // 跨列的空态行不参与卡片化（CSS 那边也按 colspan 放行）
                if (td.hasAttribute('colspan')) {
                    continue;
                }
                const label = heads[i];
                if (label && td.getAttribute('data-label') !== label) {
                    td.setAttribute('data-label', label);
                }
            }
        });
        t.classList.add('grid-tagged');
    });
}

/** 表格内容由各页自己刷新，改完 DOM 得重新标一次；防抖兜住高频刷新（实时资讯是分钟级轮询） */
let _gridLabelTimer = null;
function scheduleLabelGridTables() {
    if (_gridLabelTimer) {
        return;
    }
    _gridLabelTimer = setTimeout(function () {
        _gridLabelTimer = null;
        labelGridTables();
    }, 120);
}

document.addEventListener('DOMContentLoaded', async function () {
    // 前端构建版本标记：升级静态资源时同步改这里和各页面的 ?v=，
    // 打开 DevTools Console 看这行即可确认浏览器加载的是不是最新前端
    console.info('[影流数据] 前端版本 20261027a（v1.8.0）');
    // OAuth 回跳先收令牌（fragment 里的 #token=），再拉登录态才拉得到自己
    const oauthBack = handleAuthFragment();
    // 主题要在渲染导航之前落地，顶栏按钮才知道该显示太阳还是月亮
    applyTheme(currentTheme());
    renderThemeToggle();
    await loadMode();
    // 菜单的名称与顺序可能被超管配过，导航渲染前先拿到；拿不到就按内置的渲染
    await loadMenu();
    // 登录态决定「用户管理」是否出现、顶栏显示邮箱还是登录入口，必须在渲染导航前拿到
    await loadMe();
    renderNav();
    renderUserArea();
    // 品牌（顶栏 / 登录弹窗 / 首页）挂 logo + 名称，与菜单同一套配置，也放在导航之后
    applyBrand();
    loadLogo();
    // 页脚放最后注入：它是 appendChild 到 body 末尾的，若前面的脚本中途抛错也不会连累它
    renderFooter();
    // 窄屏把宽表格渲染成卡片，靠的是 td 上的列名（data-label）：这里统一标一次，
    // 并盯着各页后续的表格刷新（分页、轮询、重算）自动补标。只观察 childList，
    // 本函数自己不增删节点，不会自触发成死循环。
    labelGridTables();
    if (window.MutationObserver) {
        new MutationObserver(scheduleLabelGridTables).observe(document.body, {
            childList: true,
            subtree: true
        });
    }
    if (typeof initDatePickers === 'function') {
        initDatePickers(document);
    }
    document.dispatchEvent(new CustomEvent('mode:ready', {detail: MODE}));
    document.dispatchEvent(new CustomEvent('me:ready', {detail: ME}));
    if (typeof onModeReady === 'function') {
        onModeReady(MODE);
    }
    // 旧链接 /pages/login.html 会跳到 /?auth=login，这里把弹窗补上，并顺手清掉地址栏参数
    const q = new URLSearchParams(location.search);
    if (q.get('auth') === 'login') {
        if (!ME.loggedIn) {
            openAuthModal({mode: location.hash === '#register' ? 'register' : 'login'});
        }
        q.delete('auth');
        const rest = q.toString();
        history.replaceState(null, '', location.pathname + (rest ? '?' + rest : ''));
    }
    // GitHub OAuth 回跳成功：令牌已收进 cookie、登录态已拉到，给个落地提示
    if (oauthBack) {
        toast('GitHub 登录成功', 'ok');
    }
});

/**
 * 数字滚动：把元素里的数字从当前值动画到目标值。
 *
 * <p>结果区的金额、收益率这类关键数字原本是「啪」地一下换个值，
 * 加个 0.6 秒的滚动既能看清变化过程，也让页面显得有生气。
 *
 * @param {HTMLElement} el  目标元素
 * @param {number}      to  目标值
 * @param {object}      opt {fmt(v) 格式化函数, duration 毫秒}
 */
function rollNumber(el, to, opt) {
    if (!el) {
        return;
    }
    const o = opt || {};
    const fmt = o.fmt || function (v) {
        return String(v);
    };
    const duration = o.duration || 600;
    const target = Number(to);
    if (isNaN(target)) {
        el.textContent = fmt(to);
        return;
    }
    const from = parseFloat(String(el.textContent || '').replace(/[^0-9.\-]/g, ''));
    const begin = isNaN(from) ? 0 : from;
    const t0 = (window.performance && performance.now) ? performance.now() : Date.now();
    if (el._rollTimer) {
        cancelAnimationFrame(el._rollTimer);
    }

    function step(now) {
        const t = Math.min(1, (now - t0) / duration);
        // easeOutCubic：开头快、结尾稳，读数不会飘
        const e = 1 - Math.pow(1 - t, 3);
        el.textContent = fmt(begin + (target - begin) * e);
        if (t < 1) {
            el._rollTimer = requestAnimationFrame(step);
        }
    }

    el._rollTimer = requestAnimationFrame(step);
}

/** 简易分页渲染 */
function renderPager(el, page, size, total, onPage) {
    const pages = Math.max(1, Math.ceil(total / size));
    el.innerHTML = `
        <span class="total">共 ${total} 条 / ${pages} 页</span>
        <button class="btn-sm btn-ghost" ${page <= 1 ? 'disabled' : ''} data-p="1">首页</button>
        <button class="btn-sm btn-ghost" ${page <= 1 ? 'disabled' : ''} data-p="${page - 1}">上一页</button>
        <span class="dim">第 ${page} 页</span>
        <button class="btn-sm btn-ghost" ${page >= pages ? 'disabled' : ''} data-p="${page + 1}">下一页</button>
        <button class="btn-sm btn-ghost" ${page >= pages ? 'disabled' : ''} data-p="${pages}">末页</button>
    `;
    el.querySelectorAll('button[data-p]').forEach(b => {
        b.onclick = () => onPage(parseInt(b.getAttribute('data-p'), 10));
    });
}

/**
 * 弹窗分页表格：明细类表格收进弹窗，页面上只留一个按钮。
 *
 * <p>行情/回测的明细动辄几百上千行，平铺在页面上会把图表挤到很下面；
 * 收进弹窗后页面只剩图表，要看数字再点开翻页。
 *
 * @param {string} opt.title    弹窗标题
 * @param {Array}  opt.columns  [{label, cls, render(row)}]，render 返回单元格 HTML（已是 HTML，需要转义请自己调 esc）
 * @param {Array}  opt.rows     数据行
 * @param {number} [opt.pageSize] 每页条数，默认 20
 */
function openTableModal(opt) {
    const rows = opt.rows || [];
    const cols = opt.columns || [];
    const size = opt.pageSize || 20;
    let page = 1;

    let mask = document.getElementById('tableMask');
    if (!mask) {
        mask = document.createElement('div');
        mask.className = 'mask';
        mask.id = 'tableMask';
        mask.innerHTML =
            '<div class="modal" style="width:900px">'
            + '<h3 id="tmTitle"></h3>'
            + '<div style="overflow:auto;max-height:60vh">'
            + '<table class="grid"><thead id="tmHead"></thead><tbody id="tmBody"></tbody></table>'
            + '</div>'
            + '<div class="pager" id="tmPager"></div>'
            + '<div class="modal-actions">'
            + '<button class="btn-ghost" id="tmClose">关闭</button>'
            + '</div>'
            + '</div>';
        document.body.appendChild(mask);
        const hide = () => mask.classList.remove('show');
        mask.querySelector('#tmClose').addEventListener('click', hide);
        mask.addEventListener('mousedown', e => {
            if (e.target === mask) hide();
        });
    }

    const cellCls = c => (c.cls ? ` class="${c.cls}"` : '');
    mask.querySelector('#tmTitle').textContent = opt.title || '明细';
    mask.querySelector('#tmHead').innerHTML =
        '<tr>' + cols.map(c => `<th${cellCls(c)}>${esc(c.label)}</th>`).join('') + '</tr>';

    const render = () => {
        const pages = Math.max(1, Math.ceil(rows.length / size));
        if (page > pages) page = pages;
        const from = (page - 1) * size;
        const slice = rows.slice(from, from + size);
        const body = mask.querySelector('#tmBody');
        if (!slice.length) {
            body.innerHTML = `<tr><td class="empty" colspan="${Math.max(1, cols.length)}">暂无数据</td></tr>`;
        } else {
            body.innerHTML = slice.map(r =>
                '<tr>' + cols.map(c => `<td${cellCls(c)}>${c.render ? c.render(r) : ''}</td>`).join('') + '</tr>'
            ).join('');
        }
        renderPager(mask.querySelector('#tmPager'), page, size, rows.length, p => {
            page = p;
            render();
        });
    };
    render();
    mask.classList.add('show');
}

/** JSON 高亮渲染到 pre */
function renderJson(preEl, obj) {
    let text = typeof obj === 'string' ? obj : JSON.stringify(obj, null, 2);
    text = esc(text);
    text = text.replace(/&quot;(\\?.)*?&quot;/g, m => `<span style="color:#a5d6ff">${m}</span>`);
    preEl.innerHTML = text;
}
