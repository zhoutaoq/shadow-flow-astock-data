/**
 * 批量升级静态资源版本戳（?v=YYYYMMDDx）。
 *
 * 用法：改前端后跑一次 `node scripts/bump_version.js`，
 *      把 FROM 改成当前在用的版本戳、TO 改成新戳，所有页面的引用会统一换掉，
 *      浏览器就会重新拉 JS/CSS，不用让用户去清缓存。
 */
const fs = require('fs');
const path = require('path');

const FROM = process.argv[2] || '20260907f';
const TO = process.argv[3] || '20260908a';
const root = 'src/main/resources/static';
let n = 0;

function walk(d) {
  for (const f of fs.readdirSync(d)) {
    const p = path.join(d, f);
    const st = fs.statSync(p);
    if (st.isDirectory()) {
      walk(p);
    } else if (f.endsWith('.html')) {
      let s = fs.readFileSync(p, 'utf8');
      if (s.includes(FROM)) {
        s = s.split(FROM).join(TO);
        fs.writeFileSync(p, s);
        n++;
        console.log('updated', p);
      }
    }
  }
}

walk(root);
console.log('total html updated:', n, `(${FROM} -> ${TO})`);
