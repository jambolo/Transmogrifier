const fs = require('node:fs');
const path = require('node:path');
const sharp = require('sharp');
const root = path.resolve(__dirname, '..');
const walk = dir => fs.readdirSync(dir,{withFileTypes:true}).flatMap(entry => entry.isDirectory() ? walk(path.join(dir,entry.name)) : [path.join(dir,entry.name)]);

async function main() {
  const files = walk(root);
  const failures = [];
  let linksChecked = 0;
  for (const file of files) {
    if (file.endsWith('.json')) JSON.parse(fs.readFileSync(file,'utf8'));
    if (/\.(md|html|css)$/.test(file)) {
      const source = fs.readFileSync(file,'utf8');
      const refs = file.endsWith('.md') ? [...source.matchAll(/\]\(([^)]+)\)/g)].map(m=>m[1]) : [...source.matchAll(/(?:src|href)=["']([^"']+)["']|url\(['"]?([^)'"\s]+)['"]?\)/g)].map(m=>m[1]||m[2]);
      for (let ref of refs) {
        if (/^(?:[a-z]+:|#)/i.test(ref)) continue;
        ref = decodeURIComponent(ref.split('#')[0]);
        if (!ref) continue;
        linksChecked++;
        if (!fs.existsSync(path.resolve(path.dirname(file),ref))) failures.push(`${path.relative(root,file)}: missing ${ref}`);
      }
      if (file.endsWith('.md')) {
        const lines = source.split('\n');
        lines.forEach((line,i) => {
          if (/^#{1,6} /.test(line) && lines[i+1]?.trim()) failures.push(`${path.relative(root,file)}:${i+1}: no blank after heading`);
          if (/^```\s*$/.test(line) && !lines.slice(0,i).filter(l=>l.startsWith('```')).length) failures.push(`${path.relative(root,file)}:${i+1}: untyped opening fence`);
        });
      }
    }
  }
  for (const file of files.filter(f=>f.endsWith('.svg'))) {
    const source = fs.readFileSync(file,'utf8');
    if (/<(?:script|foreignObject)\b/.test(source) || /(?:href|src)=["']https?:/.test(source)) failures.push(`${file}: active or remote SVG content`);
    if (/assets[\\/]logos/.test(file) && !file.endsWith('logo-editable.svg') && /<text\b/.test(source)) failures.push(`${file}: final logo contains live text`);
    await sharp(file).metadata();
  }
  const expected = {
    'assets/illustrations/app-tiles.png':[1536,1024],
    'assets/campaign/social-intro-1200x630.png':[1200,630],
    'assets/campaign/social-game-night-1080.png':[1080,1080],
    'assets/campaign/social-lifecycle-1080.png':[1080,1080],
    'assets/campaign/feature-graphic-1024x500.png':[1024,500],
    'assets/icons/app-icon-512.png':[512,512],
    'assets/icons/favicon-16.png':[16,16]
  };
  for(const [file,[width,height]] of Object.entries(expected)) {
    const actual=await sharp(path.join(root,file)).metadata();
    if(actual.width!==width||actual.height!==height) failures.push(`${file}: wrong dimensions`);
  }
  const contrast=JSON.parse(fs.readFileSync(path.join(root,'design-tokens/contrast.json'),'utf8'));
  for(const item of contrast.filter(item=>!['outline','white'].includes(item.foreground))) if(item.ratio<4.5) failures.push(`Insufficient intended text contrast: ${JSON.stringify(item)}`);
  const title=fs.readFileSync(path.join(root,'copy/store-title.txt'),'utf8').trim();
  const short=fs.readFileSync(path.join(root,'copy/store-short-description.txt'),'utf8').trim();
  const report={files:files.length,linksChecked,svgFiles:files.filter(f=>f.endsWith('.svg')).length,pngFiles:files.filter(f=>f.endsWith('.png')).length,storeTitleCharacters:title.length,storeShortDescriptionCharacters:short.length,failures};
  console.log(JSON.stringify(report,null,2));
  if(failures.length)process.exitCode=1;
}
main().catch(error=>{console.error(error);process.exitCode=1;});
