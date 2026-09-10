/* ============ 公共前端工具 ============ */

/* ============ 运行模式 ============ */
/**
 * 由 /api/mode 填充。两种模式共用一套页面，导航按「是否依赖库表」裁剪：
 * 无数据库模式下不生成「数据更新」整组与「调用日志」「接口调试」，
 * 只剩 概览 / 分析回测（股票列表 · K线分析 · 策略回测）/ 系统配置。
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

/** 请求后端接口，统一解析 {success,message,data} */
async function api(url, options) {
    const o = options || {};
    const headers = Object.assign({'Content-Type': 'application/json'}, o.headers || {});
    const tk = waizaoToken();
    if (tk) {
        headers['X-Waizao-Token'] = tk;
    }
    try {
        const res = await fetch(url, Object.assign({}, o, {headers}));
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

/** 顶部消息条 */
function toast(message, type) {
    const box = document.getElementById('msgBox');
    if (!box) {
        alert(message);
        return;
    }
    box.className = 'msg msg-' + (type === 'err' ? 'err' : type === 'info' ? 'info' : 'ok');
    box.textContent = message;
    box.style.display = 'block';
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

/* ============ 导航：一级 + 二级（悬浮展开） ============ */
/**
 * 菜单结构：一级只留 4 项，测试/运维类入口收进「系统」的二级菜单。
 * 新增页面只改这里，各页面不再各写一份导航。
 *
 * <p>两种模式共用同一套页面：<code>db: true</code> 的项只在数据库模式显示
 * （「数据更新」整组依赖库表，「调用日志」「接口调试」也只记在库里）；
 * 一级菜单下所有子项都被隐藏时，一级菜单本身也不显示，
 * 所以数据库模式下是 概览 / 分析回测（3 项）/ 系统（系统配置）。
 *
 * <p>无数据库模式不走这套过滤，直接用下面的 {@link NAV_NO_DB} 铺平 6 个一级菜单。
 */
const NAV = [
    {text: '概览', href: '/'},
    {
        text: '分析回测', children: [
            {text: '股票列表', href: '/pages/stock-list.html'},
            {text: 'K线分析', href: '/pages/chart.html'},
            {text: '策略回测', href: '/pages/backtest.html'}
        ]
    },
    {
        text: '数据更新', db: true, children: [
            {text: '单只更新', href: '/pages/update-single.html'},
            {text: '批量更新', href: '/pages/update-batch.html'},
            {text: '条件更新', href: '/pages/update-filter.html'}
        ]
    },
    {
        text: '系统', children: [
            {text: '调用日志', href: '/pages/api-log.html', db: true},
            {text: '接口调试', href: '/pages/debug.html', db: true},
            {text: '系统配置', href: '/pages/config.html'}
        ]
    }
];

/** 当前模式下该菜单项是否可见 */
function navVisible(item) {
    return !item.db || MODE.dbEnabled;
}

/**
 * 无数据库模式的一级菜单：全部平级、不分组。
 *
 * <p>这套模式下没有库表，能用的一共就这 6 个入口，
 * 再按「分析回测 / 系统」分组只会多点一次鼠标、还容易被误读成还有别的隐藏功能。
 */
const NAV_NO_DB = [
    {text: '概览', href: '/'},
    {text: '股票列表', href: '/pages/stock-list.html'},
    {text: 'K线查看', href: '/pages/chart.html'},
    {text: '策略回测', href: '/pages/backtest.html'},
    {text: '盯盘', href: '/pages/watch.html'},
    {text: 'token配置', href: '/pages/config.html'}
];

/** 渲染导航并高亮当前页面（命中二级时一级也点亮） */
function renderNav() {
    const nav = document.getElementById('nav');
    if (!nav) {
        return;
    }
    const path = location.pathname === '/index.html' ? '/' : location.pathname;
    // 无数据库模式直接铺平这 6 个一级菜单，不套分组
    if (MODE.noDb) {
        nav.innerHTML = NAV_NO_DB.map(item => navLink(item, path)).join('');
        return;
    }
    nav.innerHTML = NAV.filter(navVisible).map(item => {
        if (!item.children) {
            return navLink(item, path);
        }
        const subs = item.children.filter(navVisible);
        if (!subs.length) {
            return '';
        }
        // 只剩一个子项时直接顶上来，省掉一层悬浮
        if (subs.length === 1) {
            return navLink(subs[0], path);
        }
        const hit = subs.some(c => path === c.href);
        const subHtml = subs.map(c =>
            `<a href="${c.href}" class="nav-sub-item${path === c.href ? ' active' : ''}">${c.text}</a>`).join('');
        return `<div class="nav-group${hit ? ' active' : ''}">
                <span class="nav-item">${item.text}<i class="caret">▾</i></span>
                <div class="nav-sub">${subHtml}</div>
            </div>`;
    }).join('');
}

function navLink(item, path) {
    return `<a class="nav-item${path === item.href ? ' active' : ''}" href="${item.href}">${item.text}</a>`;
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
            '<div class="modal">'
            + '<h3>首次使用：请填写 waizao 令牌</h3>'
            + '<div class="msg msg-info" style="display:block">'
            + '当前是<b>无数据库模式</b>：后端不保存任何参数，日K每次向供应商现取，'
            + '令牌由你填一次、存到本机浏览器（cookie，' + TOKEN_COOKIE_DAYS + ' 天有效），'
            + '之后每个请求自动带上 <code>X-Waizao-Token</code> 请求头。'
            + '</div>'
            + '<div class="field">'
            + '<label>token *</label>'
            + '<input type="password" id="tkInput" autocomplete="off" spellcheck="false"'
            + ' placeholder="粘贴 waizao 的 token">'
            + '<div class="sp-hint">令牌是按次计费的凭据，只存在本机，不会上传到别处；'
            + '可在「token配置」页里更换或清除。</div>'
            + '</div>'
            + '<div class="sp-hint" id="tkErr" style="color:var(--danger,#e5484d)"></div>'
            + '<div class="modal-actions">'
            + '<button id="tkSave">保存并使用</button>'
            + '</div>'
            + '</div>';
        document.body.appendChild(mask);

        const input = mask.querySelector('#tkInput');
        const save = () => {
            const v = input.value.trim();
            if (!v) {
                mask.querySelector('#tkErr').textContent = '令牌不能为空，请填写后再继续';
                input.focus();
                return;
            }
            setWaizaoToken(v);
            mask.classList.remove('show');
            toast('令牌已保存到本机浏览器', 'ok');
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
    mask.querySelector('#tkErr').textContent = '';
    setTimeout(() => input.focus(), 50);
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
    console.info('[金牛数据] 前端版本 20260910a（v1.2：设计令牌统一 / 数字等宽 / 去装饰动效）');
    await loadMode();
    ensureTokenDialog();
    renderNav();
    if (typeof initDatePickers === 'function') {
        initDatePickers(document);
    }
    document.dispatchEvent(new CustomEvent('mode:ready', {detail: MODE}));
    if (typeof onModeReady === 'function') {
        onModeReady(MODE);
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
