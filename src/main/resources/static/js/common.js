/* ============ 公共前端工具 ============ */

/* ============ 运行模式 ============ */
/**
 * 由 /api/mode 填充。两种模式共用一套页面，导航按「是否依赖库表」裁剪：
 * 无数据库模式下不生成「数据更新」整组与「调用日志」「接口调试」，
 * 只剩 概览 / 分析回测（股票列表 · K线分析 · 策略回测）/ 系统（系统配置 · 用户反馈）。
 * 取不到接口时按「数据库模式」渲染，避免一次请求失败导致整页没导航。
 */
const MODE = {
    dbEnabled: true,
    noDb: false,
    maxSpanDays: 1200,
    backtestPersistent: true,
    tokenFromRequest: false
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

/* ============ 令牌 ============ */
/**
 * 供应商令牌：cookie 里的自定义值优先；没自定义时用系统内置默认令牌兜底。
 * 无数据库模式下后端不保存任何参数，每次请求靠 X-Waizao-Token 请求头把令牌带过去。
 * 自定义令牌写本机 cookie（30 天有效）；内置默认令牌只内置在代码里，任何页面都不回显、不支持查看。
 */
const TOKEN_COOKIE = 'waizao_token';
const TOKEN_COOKIE_DAYS = 30;
/** 系统内置默认令牌：开箱即用；不支持在页面上查看，只能在「token配置」页覆盖 */
const DEFAULT_WAIZAO_TOKEN = '305c8974672f4bbe953e52a94a1f070c';

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

/** 当前生效的供应商令牌：cookie 自定义值优先，否则回退到内置默认令牌（因此恒非空） */
function waizaoToken() {
    return getCookie(TOKEN_COOKIE) || DEFAULT_WAIZAO_TOKEN;
}

/** 是否已有可用令牌（含内置默认）：恒为 true，填写框/等待逻辑据此不再拦截 */
function hasWaizaoToken() {
    return !!waizaoToken();
}

/** 是否设置了自定义令牌（cookie 里有值）：用于页面文案区分「默认 / 自定义」 */
function hasCustomWaizaoToken() {
    return !!getCookie(TOKEN_COOKIE);
}

function setWaizaoToken(t) {
    const v = (t || '').trim();
    if (v) {
        setCookie(TOKEN_COOKIE, v, TOKEN_COOKIE_DAYS);
    } else {
        delCookie(TOKEN_COOKIE);
    }
}

/* ============ 账号登录态 ============ */
/**
 * 登录令牌放 cookie（7 天），每个请求用 X-Auth-Token 带过去。
 *
 * <p>令牌本身在服务端内存里，进程重启即失效；这时接口会回 401，
 * 由 {@link onUnauthorized} 统一清掉本地令牌并跳登录页。
 */
const AUTH_COOKIE = 'jn_token';
const AUTH_COOKIE_DAYS = 7;

/** 当前登录态。各页面只读它，不自己发 /api/auth/me */
const ME = {
    loggedIn: false,
    user: null,
    registrationEnabled: true,
    rules: {minPasswordLength: 6, requireLetterAndDigit: true, usernamePattern: 'email'}
};

/** 登录弹窗的即时状态：当前落在登录还是注册、这一次打开要回调谁 */
let _authMode = 'login';
let _authCb = {};

function authToken() {
    return getCookie(AUTH_COOKIE);
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
                    ME.registrationEnabled = json.data.registrationEnabled !== false;
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
            + '<span class="auth-brand"><span class="mark">' + ICON.spade + '</span>金牛数据</span>'
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
            + '<button class="auth-submit" id="amSubmit" type="button">登录</button>'
            + '</div>';
        document.body.appendChild(mask);

        mask.querySelector('#amTabLogin').addEventListener('click', () => switchAuthMode('login'));
        mask.querySelector('#amTabReg').addEventListener('click', () => switchAuthMode('register'));
        mask.querySelector('#amSubmit').addEventListener('click', submitAuthModal);
        mask.querySelectorAll('.auth-eye').forEach(btn => {
            btn.addEventListener('click', () => togglePwdVisible(btn.getAttribute('data-for')));
        });
        mask.addEventListener('mousedown', e => {
            if (e.target === mask) {
                closeAuthModal(true);
            }
        });
        ['amUser', 'amPwd', 'amPwd2'].forEach(id => {
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
    ['amUser', 'amPwd', 'amPwd2'].forEach(id => {
        mask.querySelector('#' + id).value = '';
    });
    switchAuthMode(o.mode === 'register' ? 'register' : 'login');
    mask.classList.add('show');
    setTimeout(() => mask.querySelector('#amUser').focus(), 60);
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
}

/** 关掉弹窗：cancel 为真表示是用户主动放弃，要回调 onCancel */
function closeAuthModal(cancel) {
    const mask = document.getElementById('authMask');
    if (mask) {
        mask.classList.remove('show');
    }
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

    setAuthError('');
    markAuthField('');
    const btn = mask.querySelector('#amSubmit');
    const label = btn.textContent;
    btn.classList.add('loading');
    btn.innerHTML = '<span class="auth-spin"></span>' + (reg ? '注册中…' : '登录中…');
    try {
        if (reg) {
            const rr = await post('/api/auth/register', {username: user, password: pwd});
            if (!rr || !rr.success) {
                return fail((rr && rr.message) || '注册失败，请稍后再试', 'amUserField');
            }
        }
        const r = await post('/api/auth/login', {username: user, password: pwd});
        if (!r || !r.success) {
            return fail((r && r.message) || '登录失败，请稍后再试', 'amUserField');
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
    location.href = '/';
}

/** 请求后端接口，统一解析 {success,message,data} */
async function api(url, options) {
    const o = options || {};
    const headers = Object.assign({'Content-Type': 'application/json'}, o.headers || {});
    const tk = waizaoToken();
    if (tk) {
        headers['X-Waizao-Token'] = tk;
    }
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
    spade: '<svg viewBox="0 0 24 24" fill="currentColor"><path d="M12 2.6c3.4 3.5 7.3 6.3 7.3 10.1 0 2.5-1.9 4.4-4.2 4.4-1 0-1.9-.3-2.6-.9.3 1.9 1.2 3.5 2.5 4.8H9c1.3-1.3 2.2-2.9 2.5-4.8-.7.6-1.6.9-2.6.9-2.3 0-4.2-1.9-4.2-4.4C4.7 8.9 8.6 6.1 12 2.6z"/></svg>'
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

/* ============ 计费保护 ============ */
/** 预计调用次数超过这个值就弹二次确认（供应商接口按次计费，误点一次就是一批钱） */
const CONFIRM_CALLS_OVER = 20;

/**
 * 批量更新行情前的二次确认。
 *
 * <p>供应商日K接口单次请求支持任意长日期区间，所以<b>预计调用次数 = 股票只数</b>；
 * 预览模式同样消耗调用次数，因此两种模式都要确认。
 *
 * @param stockCount 本次要更新的股票只数，即预计接口调用次数
 * @param extra      追加提示（如筛选条件）
 * @returns {boolean} true 继续，false 放弃
 */
function confirmApiCost(stockCount, extra) {
    if (!stockCount || stockCount <= CONFIRM_CALLS_OVER) {
        return true;
    }
    const lines = [
        '本次将更新 ' + stockCount + ' 只股票的行情',
        '预计消耗 ' + stockCount + ' 次接口调用（每只股票整个日期区间只发一次请求）',
        '供应商接口按次计费，请求一旦发出即计费（预览模式同样计费）'
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
const THEME_KEY = 'jn_theme';

/** 已保存的主题；没存过就跟随系统偏好 */
function currentTheme() {
    try {
        const saved = localStorage.getItem(THEME_KEY);
        if (saved === 'light' || saved === 'dark') {
            return saved;
        }
    } catch (e) {
        // 隐私模式下 localStorage 不可用，退回系统偏好
    }
    return window.matchMedia && window.matchMedia('(prefers-color-scheme: light)').matches
        ? 'light' : 'dark';
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

/* ============ 导航 ============ */
/**
 * 全站菜单：一级平铺，两种运行模式共用同一份。
 *
 * <p>v1.3 起不再区分数据库 / 无数据库两套菜单 ——
 * 原先数据库模式下多出来的「数据更新」整组、「调用日志」「接口调试」全部移除：
 * 抓数落库已迁成别的入口，调用日志在无库模式按设计不落盘，接口调试本来就只在调试时用。
 * 新增页面只改这里，各页面不再各写一份导航。
 *
 * <p>{@code login: true} 的项点进去要登录（页面自己会跳登录页）；
 * {@code admin: true} 的项只有超管看得见。
 */
const NAV_ITEMS = [
    {text: '概览', href: '/'},
    {text: '股票列表', href: '/pages/stock-list.html'},
    {text: 'K线查看', href: '/pages/chart.html'},
    {text: '策略回测', href: '/pages/backtest.html', login: true},
    {text: '盯盘', href: '/pages/watch.html', login: true},
    {text: 'token配置', href: '/pages/config.html'},
    {text: '用户反馈', href: '/pages/feedback.html'},
    {text: '用户管理', href: '/pages/users.html', admin: true}
];

/** 当前身份下该菜单项是否可见 */
function navVisible(item) {
    return !item.admin || isAdmin();
}

/** 渲染导航并高亮当前页面 */
function renderNav() {
    const nav = document.getElementById('nav');
    if (!nav) {
        return;
    }
    const path = location.pathname === '/index.html' ? '/' : location.pathname;
    nav.innerHTML = NAV_ITEMS.filter(navVisible).map(item => navLink(item, path)).join('');
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
}

function navLink(item, path) {
    const flag = item.login && !isLogin() ? ' data-login="1"' : '';
    return `<a class="nav-item${path === item.href ? ' active' : ''}" href="${item.href}"${flag}>${item.text}</a>`;
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
    box.innerHTML = '<div class="ua-group">'
        + '<button class="ua-btn" type="button" aria-haspopup="menu">'
        + '<span class="ua-avatar">' + esc(initial) + '</span>'
        + '<span class="ua-name">' + esc(u.username) + '</span>'
        + (u.admin ? '<span class="ua-role">超管</span>' : '')
        + '<i class="ua-caret">▾</i></button>'
        + '<div class="ua-menu" role="menu">'
        + '<a href="javascript:void(0)" id="uaPwd" role="menuitem">' + ICON.pwd + '修改密码</a>'
        + '<a href="javascript:void(0)" id="uaLogout" class="danger" role="menuitem">' + ICON.out + '退出登录</a>'
        + '</div></div>';
    box.querySelector('#uaPwd').addEventListener('click', openPasswordModal);
    box.querySelector('#uaLogout').addEventListener('click', doLogout);
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

/**
 * 无数据库模式下的令牌兜底填写框。系统内置了默认令牌后，正常永远不会再弹；
 * 只有当内置默认被清空、且本机也没有自定义令牌时才要求填写，不填不放行。
 *
 * <p>数据库模式的令牌维护在配置表，不需要这一步。
 */
function ensureTokenDialog() {
    if (!MODE.tokenFromRequest || hasWaizaoToken()) {
        return false;
    }
    let mask = document.getElementById('tokenMask');
    if (!mask) {
        mask = document.createElement('div');
        mask.className = 'mask';
        mask.id = 'tokenMask';
        mask.innerHTML =
            '<div class="modal modal-auth">'
            + '<div class="auth-head">'
            + '<span class="auth-brand"><span class="mark">' + ICON.pwd + '</span>需要数据接口令牌</span>'
            + '</div>'
            + '<div class="auth-field">'
            + '<label for="tkInput">令牌</label>'
            + '<div class="auth-input-wrap has-toggle">'
            + '<input type="password" id="tkInput" autocomplete="off" spellcheck="false"'
            + ' placeholder="粘贴数据接口令牌">'
            + '<button class="auth-eye" type="button" data-for="tkInput" aria-label="显示令牌">' + ICON.eye + '</button>'
            + '</div>'
            + '</div>'
            + '<div class="auth-alert" id="tkErr">' + ICON.err + '<span id="tkErrTxt"></span></div>'
            + '<button class="auth-submit" id="tkSave" type="button">保存并使用</button>'
            + '</div>';
        document.body.appendChild(mask);

        const input = mask.querySelector('#tkInput');
        mask.querySelector('.auth-eye').addEventListener('click', () => {
            const show = input.type === 'password';
            input.type = show ? 'text' : 'password';
            mask.querySelector('.auth-eye').innerHTML = show ? ICON.eyeOff : ICON.eye;
        });
        const save = () => {
            const v = input.value.trim();
            if (!v) {
                mask.querySelector('#tkErrTxt').textContent = '令牌不能为空';
                mask.querySelector('#tkErr').classList.add('show');
                input.focus();
                return;
            }
            setWaizaoToken(v);
            mask.classList.remove('show');
            toast('令牌已保存', 'ok');
            document.dispatchEvent(new CustomEvent('token:ready', {detail: {token: v}}));
        };
        mask.querySelector('#tkSave').addEventListener('click', save);
        input.addEventListener('keydown', e => {
            if (e.key === 'Enter') {
                e.preventDefault();
                save();
            }
        });
        // 遮罩不接受"点外面关掉"：没有令牌时任何功能都跑不动
        mask.addEventListener('mousedown', e => e.stopPropagation());
    }
    mask.classList.add('show');
    const input = mask.querySelector('#tkInput');
    input.value = '';
    input.type = 'password';
    mask.querySelector('.auth-eye').innerHTML = ICON.eye;
    mask.querySelector('#tkErr').classList.remove('show');
    setTimeout(() => input.focus(), 60);
    return true;
}

/**
 * 等令牌就绪：页面在解析阶段（早于 DOMContentLoaded）就要发请求时用。
 *
 * <p>没有它，首进页面会先发出一批没有令牌的请求，白挨几条报错提示。
 */
let _tokenReadyPromise = null;
function whenTokenReady() {
    if (!_tokenReadyPromise) {
        _tokenReadyPromise = new Promise(resolve => {
            if (!MODE.tokenFromRequest || hasWaizaoToken()) {
                resolve();
                return;
            }
            document.addEventListener('token:ready', () => resolve(), {once: true});
        });
    }
    return _tokenReadyPromise;
}

document.addEventListener('DOMContentLoaded', async function () {
    // 前端构建版本标记：升级静态资源时同步改这里和各页面的 ?v=，
    // 打开 DevTools Console 看这行即可确认浏览器加载的是不是最新前端
    console.info('[金牛数据] 前端版本 20260912g（v1.3.6：盯盘页下拉遮挡修复 / 非交易时段静默）');
    // 主题要在渲染导航之前落地，顶栏按钮才知道该显示太阳还是月亮
    applyTheme(currentTheme());
    renderThemeToggle();
    await loadMode();
    ensureTokenDialog();
    // 登录态决定「用户管理」是否出现、顶栏显示邮箱还是登录入口，必须在渲染导航前拿到
    await loadMe();
    renderNav();
    renderUserArea();
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
