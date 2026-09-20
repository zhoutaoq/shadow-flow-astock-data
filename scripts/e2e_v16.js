/*
 * v1.6.0 接口自测（对着真实跑起来的 jar，只用 Node 内置 fetch —— 本机 curl 被代理 shim 拦）
 *
 * 跑之前先把服务起在一个临时数据目录上（避免动本地 ./data）：
 *   java -jar shadow-flow-biz/target/shadow-flow-biz.jar \
 *        --server.port=8099 --shadowflow.store.data-dir=<临时目录> \
 *        --shadowflow.api.enabled=false --watch.enabled=false \
 *        --shadowflow.notify.enabled=false
 *
 * 然后：
 *   node scripts/e2e_v16.js [baseUrl]
 */
const BASE = process.argv[2] || 'http://127.0.0.1:8099';

let failed = 0;
function ok(cond, name, extra) {
    console.log((cond ? '  PASS  ' : '  FAIL  ') + name + (extra ? '  → ' + extra : ''));
    if (!cond) failed++;
}

let token = '';

async function call(method, path, body) {
    const headers = {'Content-Type': 'application/json'};
    if (token) headers['X-Auth-Token'] = token;
    const res = await fetch(BASE + path, {method, headers, body: body ? JSON.stringify(body) : undefined});
    let json = null;
    try {
        json = await res.json();
    } catch (e) {
        // 非 JSON 响应（静态资源等）留空
    }
    return {status: res.status, json, res};
}

(async function main() {
    console.log('== 1. 静态资源与品牌 ==');
    const home = await fetch(BASE + '/');
    const html = await home.text();
    ok(home.status === 200, '首页 200', '实测 ' + home.status);
    ok(html.indexOf('<title>影流数据</title>') >= 0, '首页标题为「影流数据」');
    ok(html.indexOf('金牛数据') === -1, '首页无旧品牌名');
    ok(html.indexOf('计费') === -1 && html.indexOf('供应商') === -1, '首页无成本/来源类表述');

    const css = await fetch(BASE + '/css/common.css?v=20260920d');
    const cssText = await css.text();
    ok(css.status === 200, 'common.css 带版本戳可取', '实测 ' + css.status);
    ok(/\.hint-q\s*\{/.test(cssText) && /\.hint-box\s*\{/.test(cssText),
        '下发的 CSS 含 ? 气泡组件');

    console.log('== 2. 登录 ==');
    const login = await call('POST', '/api/auth/login', {username: 'admin', password: '1'});
    ok(!!login.json && login.json.success === true, 'admin 登录成功',
        login.json ? JSON.stringify(login.json.message) : 'status=' + login.status);
    token = (login.json && login.json.data && login.json.data.token) || '';
    ok(!!token, '拿到登录令牌');
    ok(!!(login.json && login.json.data && login.json.data.user && login.json.data.user.admin),
        '登录用户带 admin 标记');

    console.log('== 3. 常用接口 ==');
    const mode = await call('GET', '/api/mode');
    ok(!!mode.json && mode.json.success === true, '/api/mode 200');
    ok(mode.json && mode.json.data && mode.json.data.dbEnabled === false,
        '跑在无数据库模式（本次自测的口径）',
        mode.json && mode.json.data ? 'dbEnabled=' + mode.json.data.dbEnabled : '');

    const ver = await call('GET', '/api/version');
    ok(!!ver.json && ver.json.success === true, '/api/version 200',
        ver.json && ver.json.data ? JSON.stringify(ver.json.data) : '');

    const me = await call('GET', '/api/auth/me');
    ok(!!me.json && me.json.success === true && me.json.data.loggedIn === true,
        '/api/auth/me 已登录');

    const hero = await call('GET', '/api/home/hero');
    ok(!!hero.json && hero.json.success === true, '/api/home/hero 200');

    console.log('== 4. 系统配置页依赖的接口 ==');
    const link = await call('GET', '/api/stock-link');
    ok(!!link.json && link.json.success === true, '/api/stock-link 200');
    ok(!!(link.json && link.json.data && link.json.data.targets && link.json.data.targets.length),
        '跳转目标非空',
        link.json && link.json.data ? link.json.data.targets.map(t => t.key).join(',') : '');

    const ig = await call('GET', '/api/admin/integration');
    ok(!!ig.json && ig.json.success === true, '/api/admin/integration 200');
    const items = (ig.json && ig.json.data && ig.json.data.items) || [];
    ok(items.length > 0, `对接项 ${items.length} 条`);
    ok(items.every(it => it.hint && it.hint.trim().length > 0),
        '每个对接项都带说明（否则气泡是空的）',
        items.filter(it => !it.hint).map(it => it.name).join(','));
    const allHint = items.map(it => it.hint || '').join(' ');
    ok(allHint.indexOf('计费') === -1 && allHint.indexOf('供应商') === -1 && allHint.indexOf('免费') === -1,
        '对接项说明里无成本/来源类表述');
    ok(items.every(it => it.secret === false || (it.display || '').indexOf('****') >= 0 || !it.configured),
        '密文字段一律打码回显（不外泄明文）');

    console.log('== 5. 未授权必须被拦 ==');
    const saved = token;
    token = '';
    const denied = await call('GET', '/api/admin/integration');
    ok(denied.status === 401, '匿名访问 /api/admin/integration 返回 401', '实测 ' + denied.status);
    token = saved;

    console.log('');
    console.log(failed === 0 ? '全部通过' : failed + ' 项失败');
    process.exit(failed === 0 ? 0 : 1);
})().catch(e => {
    console.error('接口自测异常:', e);
    process.exit(1);
});
