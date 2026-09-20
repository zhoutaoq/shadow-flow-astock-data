/* ============ 公共前端工具 ============ */

/* ============ 运行模式 ============ */
/**
 * 由 /api/mode 填充。两种模式共用一套页面，导航按「是否依赖库表」裁剪：
 * 无数据库模式下不生成「数据更新」整组与「调用日志」「接口调试」，
 * 只剩 概览 / 分析回测（股票列表 · K线分析 · 策略回测）/ 系统（实时通知配置 · 用户反馈）。
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
            + '<span class="auth-brand"><span class="mark">' + ICON.spade + '</span>影流数据</span>'
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
    swap: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round"><path d="M4 8h13M14 4.8L17.2 8 14 11.2"/><path d="M20 16H7M10 12.8L6.8 16 10 19.2"/></svg>'
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
    {text: '实时资讯', href: '/pages/news.html'},
    {text: '策略回测', href: '/pages/backtest.html', login: true},
    {text: '盯盘', href: '/pages/watch.html', login: true},
    {text: '我的账户', href: '/pages/account.html', login: true},
    {text: '用户反馈', href: '/pages/feedback.html'},
    {text: '系统配置', href: '/pages/system.html', admin: true},
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

/* ============ 全站页脚 ============ */

/** 页脚署名。改这里即可，全站 14 个页面同步（页脚是注入的，页面里没有副本） */
const SITE_AUTHOR = 'zhoutaoqiang';

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
    console.info('[影流数据] 前端版本 20260920e（v1.6.0：品牌改影流数据 / 多模块拆分 / 通知 SDK / 提示文案收进 ? 气泡）');
    // 主题要在渲染导航之前落地，顶栏按钮才知道该显示太阳还是月亮
    applyTheme(currentTheme());
    renderThemeToggle();
    await loadMode();
    // 登录态决定「用户管理」是否出现、顶栏显示邮箱还是登录入口，必须在渲染导航前拿到
    await loadMe();
    renderNav();
    renderUserArea();
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
