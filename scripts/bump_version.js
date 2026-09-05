const fs = require('fs');
const path = require('path');

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
      if (s.includes('20260907e')) {
        s = s.split('20260907e').join('20260907f');
        fs.writeFileSync(p, s);
        n++;
        console.log('updated', p);
      }
    }
  }
}

walk(root);
console.log('total html updated:', n);
