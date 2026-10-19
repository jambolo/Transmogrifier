const fs = require('node:fs');
const path = require('node:path');
const { GlobalFonts, convertSVGTextToPath } = require('@napi-rs/canvas');
const sharp = require('sharp');

const root = path.resolve(__dirname, '..');
const assets = path.join(root, 'assets');
const name = 'Transmogrifier';
const colors = {
  ink: '#252336', paper: '#FFF9F0', white: '#FFFFFF', citrus: '#E9F56A',
  coral: '#FF795E', sky: '#B8DEF0', lilac: '#D5C7F4', muted: '#615E6C',
  outline: '#817B89', error: '#A52D37', success: '#236247', darkSurface: '#333044'
};
const tokens = {
  version: '1.0.0', name, colors,
  font: { display: 'Bricolage Grotesque', body: 'Manrope', fallback: 'system-ui, sans-serif' },
  type: { display: [64, 64, 800], heading: [40, 44, 700], title: [28, 34, 700], body: [16, 24, 400], label: [14, 20, 700], caption: [12, 18, 500] },
  spacing: [4, 8, 12, 16, 24, 32, 48, 64],
  radius: { control: 16, card: 24, pill: 999 },
  motion: { feedbackMs: 120, transitionMs: 200, easing: 'cubic-bezier(0.2, 0, 0, 1)' },
  light: { background: 'paper', surface: 'white', onSurface: 'ink', primary: 'ink', onPrimary: 'paper', primaryContainer: 'citrus', onPrimaryContainer: 'ink', secondaryContainer: 'sky', onSecondaryContainer: 'ink', outline: 'outline', error: 'error', onError: 'white', focus: 'ink' },
  dark: { background: 'ink', surface: 'darkSurface', onSurface: 'paper', primary: 'citrus', onPrimary: 'ink', primaryContainer: 'citrus', onPrimaryContainer: 'ink', secondaryContainer: 'sky', onSecondaryContainer: 'ink', outline: 'lilac', error: 'coral', onError: 'ink', focus: 'citrus' }
};
const write = (file, content) => {
  const full = path.join(root, file);
  fs.mkdirSync(path.dirname(full), { recursive: true });
  fs.writeFileSync(full, content);
};
for (const weight of ['Regular','Medium','SemiBold','Bold','ExtraBold']) {
  GlobalFonts.registerFromPath(path.join(assets, `fonts/Manrope-${weight}.ttf`), 'Manrope');
}
GlobalFonts.registerFromPath(path.join(assets, 'fonts/BricolageGrotesque-Variable.ttf'), 'Bricolage Grotesque');

const esc = value => value.replaceAll('&', '&amp;').replaceAll('"', '&quot;').replaceAll('<', '&lt;');
const svg = (w, h, body, title) => `<svg xmlns="http://www.w3.org/2000/svg" width="${w}" height="${h}" viewBox="0 0 ${w} ${h}" role="img" aria-label="${esc(title)}"><title>${esc(title)}</title>${body}</svg>`;
const rect = (x,y,w,h,r,fill) => `<rect x="${x}" y="${y}" width="${w}" height="${h}" rx="${r}" fill="${fill}"/>`;
const text = (x,y,value,size=24,weight=500,fill=colors.ink,family='Manrope') => `<text x="${x}" y="${y}" font-family="${family}" font-size="${size}" font-weight="${weight}" fill="${fill}">${esc(value)}</text>`;
const display = (x,y,value,size=64,fill=colors.ink) => text(x,y,value,size,800,fill,'Bricolage Grotesque');
const tilePath = 'M26 18H49Q58 18 58 27V31Q58 39 66 39H70Q78 39 78 47V66Q78 80 64 80H26Q12 80 12 66V32Q12 18 26 18Z';
const cornerPath = 'M73 8H79Q86 8 86 15V21Q86 28 79 28H73Q66 28 66 21V15Q66 8 73 8Z';
const mark = (fill=colors.ink) => `<path fill="${fill}" d="${tilePath} ${cornerPath}"/>`;
const positionedMark = (x,y,s,fill=colors.ink) => `<g transform="translate(${x} ${y}) scale(${s})">${mark(fill)}</g>`;
const lockup = (x,y,s=1,fill=colors.ink) => `<g transform="translate(${x} ${y}) scale(${s})">${positionedMark(0,0,1,fill)}${display(106,69,name,58,fill)}</g>`;

function outlined(file,w,h,body,title) {
  const source = svg(w,h,body,title);
  let result = convertSVGTextToPath(source).toString();
  result = result.replace('<svg ', `<svg role="img" aria-label="${esc(title)}" `);
  result = result.replace(/(<svg[^>]*>)/, `$1<title>${esc(title)}</title>`);
  write(file,result);
}

async function png(source,target,width) {
  const input = path.join(root,source);
  await sharp(input, { density: 144 }).resize({ width }).png().toFile(path.join(root,target));
}

function luminance(hex) {
  const c = hex.slice(1).match(/../g).map(v => parseInt(v,16)/255).map(v => v <= .04045 ? v/12.92 : ((v+.055)/1.055)**2.4);
  return .2126*c[0]+.7152*c[1]+.0722*c[2];
}
const contrast = (a,b) => { const [lo,hi] = [luminance(colors[a]),luminance(colors[b])].sort((a,b)=>a-b); return +((hi+.05)/(lo+.05)).toFixed(2); };

async function main() {
  write('design-tokens/tokens.json',JSON.stringify(tokens,null,2)+'\n');
  write('design-tokens/tokens.css',`:root {\n${Object.entries(colors).map(([k,v])=>`  --brand-${k.replace(/[A-Z]/g,m=>'-'+m.toLowerCase())}: ${v};`).join('\n')}\n  --font-display: "Bricolage Grotesque", system-ui, sans-serif;\n  --font-body: "Manrope", system-ui, sans-serif;\n  --radius-control: 16px;\n  --radius-card: 24px;\n  --motion-fast: 120ms;\n  --motion-normal: 200ms;\n}\n`);
  const pairs = [['ink','paper'],['ink','citrus'],['ink','coral'],['ink','sky'],['ink','lilac'],['muted','paper'],['muted','white'],['paper','ink'],['paper','darkSurface'],['citrus','darkSurface'],['error','paper'],['white','error'],['outline','paper'],['outline','white'],['success','paper'],['white','coral'],['white','citrus']];
  const ratios = pairs.map(([foreground,background])=>({foreground,background,ratio:contrast(foreground,background)}));
  write('design-tokens/contrast.json',JSON.stringify(ratios,null,2)+'\n');

  write('assets/logos/symbol-ink.svg',svg(96,96,mark(),'Transmogrifier symbol'));
  write('assets/logos/symbol-paper.svg',svg(96,96,mark(colors.paper),'Transmogrifier symbol, light'));
  write('assets/logos/symbol-citrus.svg',svg(96,96,mark(colors.citrus),'Transmogrifier symbol, citrus'));
  outlined('assets/logos/logo-primary.svg',512,112,lockup(8,8),name);
  outlined('assets/logos/logo-reverse.svg',512,112,lockup(8,8,1,colors.paper),name);
  outlined('assets/logos/logo-black.svg',512,112,lockup(8,8,1,'#000000'),name);
  outlined('assets/logos/wordmark.svg',400,92,display(5,65,name,58),name);
  outlined('assets/logos/logo-stacked.svg',512,250,positionedMark(192,5,1.3)+display(63,204,name,58),name);
  write('assets/logos/logo-editable.svg',svg(512,112,lockup(8,8),name+' editable text master'));
  write('assets/icons/app-icon.svg',svg(512,512,rect(0,0,512,512,0,colors.citrus)+positionedMark(60,80,4),'Transmogrifier app icon'));
  write('assets/icons/favicon.svg',svg(64,64,rect(0,0,64,64,14,colors.citrus)+positionedMark(3,7,.59),'Transmogrifier'));

  const icons = {
    plus: '<path d="M12 5v14M5 12h14"/>',
    check: '<path d="m5 12 4 4L19 6"/>',
    list: '<path d="M9 6h11M9 12h11M9 18h11"/><path d="M4 6h.01M4 12h.01M4 18h.01"/>',
    dice: '<rect x="3" y="3" width="18" height="18" rx="4"/><path d="M8 8h.01M16 8h.01M8 16h.01M16 16h.01"/>',
    shuffle: '<path d="M3 6h3c5 0 7 12 12 12h3m-4-4 4 4-4 4M3 18h3c5 0 7-12 12-12h3m-4-4 4 4-4 4"/>',
    'arrow-right': '<path d="M4 12h16m-6-6 6 6-6 6"/>',
    edit: '<path d="m14 5 5 5M4 20l1-6L16 3a2 2 0 0 1 3 0l2 2a2 2 0 0 1 0 3L10 19z"/>',
    delete: '<path d="M3 6h18M9 6V3h6v3M6 6l1 15h10l1-15M10 10v7M14 10v7"/>',
    close: '<path d="m6 6 12 12M6 18 18 6"/>',
    retry: '<path d="M20 7A8 8 0 1 0 21 14M20 3v5h-5"/>',
    play: '<path d="m8 4 12 8-12 8z"/>',
    suitcase: '<rect x="3" y="7" width="18" height="14" rx="3"/><path d="M8 7V3h8v4M8 11v6M16 11v6"/>'
  };
  for (const [key,body] of Object.entries(icons)) write(`assets/icons/${key}.svg`,svg(24,24,`<g stroke="${colors.ink}" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" fill="none">${body}</g>`,key.replaceAll('-',' ')+' icon'));

  const icon = (key,x,y,size=64) => `<g transform="translate(${x} ${y}) scale(${size/24})" stroke="${colors.ink}" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" fill="none">${icons[key]}</g>`;
  const card = (x,y,fill,key,rotation=0) => `<g transform="rotate(${rotation} ${x+95} ${y+110})">${rect(x+6,y+8,190,220,28,colors.ink)}${rect(x,y,190,220,28,fill)}${icon(key,x+59,y+72,72)}</g>`;
  let body = rect(0,0,1200,630,0,colors.paper)+lockup(44,34,.65)+display(48,230,'Little apps.',88)+display(48,322,'For right now.',88)+text(52,384,'Describe it. Try it. Make it yours.',25)+card(818,100,colors.citrus,'check',-8)+card(948,267,colors.coral,'dice',10)+card(740,336,colors.sky,'list',-5)+text(52,548,'EARLY ANDROID VERSION',15,800)+text(52,578,'Claude API key required. API usage charges apply.',17,500);
  outlined('assets/campaign/social-intro-1200x630.svg',1200,630,body,'Transmogrifier — Little apps. For right now.');
  body = rect(0,0,1080,1080,0,colors.coral)+lockup(48,36,.8)+text(58,232,'ONE EVENING. ONE LITTLE APP.',20,800)+display(54,350,'Tonight needs',92)+display(54,446,'a scorekeeper.',92)+rect(58,510,964,356,36,colors.paper)+text(94,559,'EXAMPLE APP · DESIGN CONCEPT',16,700)+display(140,655,'Team A',39)+display(660,655,'Team B',39)+display(172,790,'08',120)+display(694,790,'06',120)+text(60,958,'Little apps. For right now.',29,700)+text(60,1010,'Early Android version · Claude API key and usage charges required.',21,500);
  outlined('assets/campaign/social-game-night-1080.svg',1080,1080,body,'Transmogrifier — Tonight needs a scorekeeper. Example app concept.');
  body = rect(0,0,1080,1080,0,colors.ink)+lockup(48,36,.8,colors.paper)+display(55,350,'Make it.',130,colors.citrus)+display(55,500,'Use it.',130,colors.paper)+display(55,650,'Move on.',130,colors.paper)+text(61,765,'Your apps stay until you delete them.',32,500,colors.paper)+positionedMark(820,695,1.8,colors.citrus)+text(61,972,'Early Android version. Claude API key required.',23,500,colors.paper)+text(61,1010,'API usage charges apply.',23,500,colors.paper);
  outlined('assets/campaign/social-lifecycle-1080.svg',1080,1080,body,'Transmogrifier — Make it. Use it. Move on. Apps stay until you delete them.');
  body = rect(0,0,1024,500,0,colors.citrus)+lockup(34,25,.72)+display(41,215,'Little apps.',77)+display(41,300,'For right now.',77)+card(742,157,colors.paper,'check',9)+text(46,414,'Early Android version. Claude API key required.',20,600)+text(46,447,'API usage charges apply.',20,600);
  outlined('assets/campaign/feature-graphic-1024x500.svg',1024,500,body,'Transmogrifier feature graphic concept');

  const pattern = rect(0,0,600,600,0,colors.paper)+[[20,20,colors.citrus],[320,20,colors.sky],[20,320,colors.lilac],[320,320,colors.coral]].map(([x,y,c],i)=>`<g transform="translate(${x} ${y}) rotate(${i%2?8:-8} 120 120)">${rect(20,20,200,200,32,c)}${positionedMark(70,70,1.1)}</g>`).join('');
  write('assets/illustrations/tile-pattern.svg',svg(600,600,pattern,'Decorative utility tile pattern'));
  write('assets/illustrations/empty-state.svg',svg(320,240,rect(100,38,128,158,22,colors.sky)+`<g transform="rotate(-9 144 124)">${rect(62,48,138,162,24,colors.citrus)}${icon('plus',105,104,52)}</g>`,'An empty app card with a plus sign'));

  const foreground = `<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108"><group android:scaleX="0.6" android:scaleY="0.6" android:translateX="24.6" android:translateY="27.6"><path android:fillColor="${colors.ink}" android:pathData="${tilePath} ${cornerPath}"/></group></vector>\n`;
  write('assets/android/drawable/brand_icon_foreground.xml',foreground);
  write('assets/android/drawable/brand_icon_monochrome.xml',foreground.replace(colors.ink,'#FFFFFFFF'));
  write('assets/android/values/brand_colors.xml',`<resources><color name="brand_icon_background">${colors.citrus}</color></resources>\n`);
  write('assets/android/mipmap-anydpi-v26/brand_launcher.xml','<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android"><background android:drawable="@color/brand_icon_background"/><foreground android:drawable="@drawable/brand_icon_foreground"/></adaptive-icon>\n');
  write('assets/android/mipmap-anydpi-v33/brand_launcher.xml','<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android"><background android:drawable="@color/brand_icon_background"/><foreground android:drawable="@drawable/brand_icon_foreground"/><monochrome android:drawable="@drawable/brand_icon_monochrome"/></adaptive-icon>\n');

  await png('assets/logos/logo-primary.svg','assets/logos/logo-primary.png',1520);
  await png('assets/logos/logo-reverse.svg','assets/logos/logo-reverse.png',1520);
  for (const size of [512,192,180]) await png('assets/icons/app-icon.svg',`assets/icons/app-icon-${size}.png`,size);
  for (const size of [16,32,48]) await png('assets/icons/favicon.svg',`assets/icons/favicon-${size}.png`,size);
  for (const [file,width] of [['social-intro-1200x630',1200],['social-game-night-1080',1080],['social-lifecycle-1080',1080],['feature-graphic-1024x500',1024]]) await png(`assets/campaign/${file}.svg`,`assets/campaign/${file}.png`,width);
  console.log(JSON.stringify({assetBuild:'complete',contrast:ratios},null,2));
}
main().catch(error=>{console.error(error);process.exitCode=1;});
