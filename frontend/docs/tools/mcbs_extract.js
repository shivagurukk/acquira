// Extract acquirer-related MCBS report specs from Mastercard DITA XML -> single HTML
const fs = require('fs'), path = require('path');
const dir = process.argv[2], out = process.argv[3];

const decode = s => s.replace(/&amp;/g,'&').replace(/&lt;/g,'<').replace(/&gt;/g,'>')
  .replace(/&#8217;|&rsquo;/g,'’').replace(/&quot;/g,'"').replace(/&#(\d+);/g,(m,d)=>String.fromCharCode(+d))
  .replace(/&nbsp;/g,' ');
const stripTags = s => decode(s.replace(/<[^>]+>/g,' ')).replace(/\s+/g,' ').trim();
const esc = s => s.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');

function extractTables(xml){
  const tables = [];
  const tblRe = /<table[\s\S]*?<\/table>/g; let m;
  while((m = tblRe.exec(xml))){
    const t = m[0];
    const titleM = t.match(/<title>([\s\S]*?)<\/title>/);
    const rows = [];
    const rowRe = /<row>([\s\S]*?)<\/row>/g; let r;
    while((r = rowRe.exec(t))){
      const cells = [];
      const cellRe = /<entry[^>]*>([\s\S]*?)<\/entry>/g; let c;
      while((c = cellRe.exec(r[1]))) cells.push(stripTags(c[1]));
      if(cells.length) rows.push(cells);
    }
    // header = rows inside thead
    const theadM = t.match(/<thead>([\s\S]*?)<\/thead>/);
    let headerCount = 0;
    if(theadM) headerCount = (theadM[1].match(/<row>/g)||[]).length;
    tables.push({title: titleM?stripTags(titleM[1]):null, rows, headerCount});
  }
  return tables;
}

const files = fs.readdirSync(dir).filter(f=>f.endsWith('.xml'));
const topics = [];
for(const f of files){
  const xml = fs.readFileSync(path.join(dir,f),'utf8');
  const titleM = xml.match(/<title>([\s\S]*?)<\/title>/);
  if(!titleM) continue;
  const title = stripTags(titleM[1]);
  const shortM = xml.match(/<shortdesc>([\s\S]*?)<\/shortdesc>/);
  const tables = extractTables(xml);
  // audience: row whose first cell is "Audience"
  let audience = null;
  for(const t of tables) for(const row of t.rows)
    if(row[0] && row[0].toLowerCase()==='audience'){ audience = row[1]||''; }
  const a=(audience||'').toLowerCase(); const isAcquirer = audience ? (a.includes('acquirer')||/all|all customers/.test(a)) : null;
  // structural layouts relevant to any billed customer (incl. acquirer)
  const structural = /T0CH|BFIL|TN3A|T0CF|Monthly Billing Summary|Weekly Billing Summary|GB073010|Monthly Summarized Billing|Invoice Detail|Billing Collection|Bulk Data File/i.test(title);
  if((isAcquirer===true) || (isAcquirer===null && structural)){
    topics.push({title, short: shortM?stripTags(shortM[1]):'', audience, tables, file:f});
  }
}
topics.sort((a,b)=>a.title.localeCompare(b.title));


if(out.endsWith('.json')){
  const slim = topics.map(t=>({title:t.title, short:t.short, audience:t.audience,
    tables:t.tables.filter(x=>x.rows.length).map(x=>({title:x.title, headerCount:x.headerCount, rows:x.rows}))}));
  fs.writeFileSync(out, JSON.stringify({source:'Mastercard Consolidated Billing System manual, 2 June 2026 edition', extractedAt:'2026-09-01', topicCount:slim.length, topics:slim}, null, 1));
  console.log('json topics:', slim.length, 'bytes:', fs.statSync(out).size);
} else { console.log('html mode removed'); }

/* ────────────────────────────────────────────────────────────────────
 * USAGE (regenerating frontend/src/data/mcbsAcquirerReports.json)
 *
 *   1. Unzip the Mastercard "Consolidated Billing System Reports"
 *      DITA-XML archive (from Mastercard Connect) into a folder.
 *   2. node docs/tools/mcbs_extract.js <xml-folder> frontend/src/data/mcbsAcquirerReports.json
 *
 * Filter: topics whose Audience includes Acquirer/All, plus the
 * audience-less invoice-file layouts (T0CH/BFIL, TN3A, T0CF, billing
 * summaries). Emitting a path ending in .html instead produces the
 * printable HTML (Chrome headless --print-to-pdf turns it into the
 * reference PDF). Update the extractedAt/source strings on refresh.
 * ──────────────────────────────────────────────────────────────────── */
