#!/usr/bin/env node
/*
 * Renders the altar's elements and backgrounds, so the app ships no third-party art.
 * Run with Playwright on NODE_PATH: node tools/make_altar_art.js [output dir]
 * Everything is lit from the upper left, like a room with one candle.
 */
const { chromium } = require("playwright");
const fs = require("fs");
const path = require("path");

const OUT = process.argv[2] || "app/src/main/res/drawable-nodpi";

const DEFS = `
<filter id="hammered" x="-5%" y="-5%" width="110%" height="110%">
  <feTurbulence type="fractalNoise" baseFrequency="0.045" numOctaves="3" seed="4"/>
  <feDiffuseLighting surfaceScale="3.2" lighting-color="#fff" result="light"><feDistantLight azimuth="225" elevation="58"/></feDiffuseLighting>
  <feBlend in="SourceGraphic" in2="light" mode="multiply"/>
  <feComposite in2="SourceAlpha" operator="in"/>
</filter>
<filter id="fine" x="-5%" y="-5%" width="110%" height="110%">
  <feTurbulence type="fractalNoise" baseFrequency="0.5" numOctaves="4" seed="9"/>
  <feDiffuseLighting surfaceScale="1.3" lighting-color="#fff" result="light"><feDistantLight azimuth="225" elevation="62"/></feDiffuseLighting>
  <feBlend in="SourceGraphic" in2="light" mode="multiply"/>
  <feComposite in2="SourceAlpha" operator="in"/>
</filter>
<filter id="rock" x="-5%" y="-5%" width="110%" height="110%">
  <feTurbulence type="fractalNoise" baseFrequency="0.09" numOctaves="5" seed="12"/>
  <feDiffuseLighting surfaceScale="2.6" lighting-color="#fff" result="light"><feDistantLight azimuth="225" elevation="55"/></feDiffuseLighting>
  <feBlend in="SourceGraphic" in2="light" mode="multiply"/>
  <feComposite in2="SourceAlpha" operator="in"/>
</filter>
<filter id="grain" x="-5%" y="-5%" width="110%" height="110%">
  <feTurbulence type="fractalNoise" baseFrequency="0.012 0.22" numOctaves="4" seed="3"/>
  <feDiffuseLighting surfaceScale="2.2" lighting-color="#fff" result="light"><feDistantLight azimuth="200" elevation="60"/></feDiffuseLighting>
  <feBlend in="SourceGraphic" in2="light" mode="multiply"/>
  <feComposite in2="SourceAlpha" operator="in"/>
</filter>
<filter id="emboss" x="-10%" y="-10%" width="120%" height="120%">
  <feGaussianBlur in="SourceAlpha" stdDeviation="2.4" result="b"/>
  <feSpecularLighting in="b" surfaceScale="5" specularConstant="1" specularExponent="22" lighting-color="#fff2c8" result="s"><feDistantLight azimuth="225" elevation="48"/></feSpecularLighting>
  <feComposite in="s" in2="SourceAlpha" operator="in" result="shine"/>
  <feOffset in="SourceAlpha" dx="3" dy="4"/><feGaussianBlur stdDeviation="3"/><feColorMatrix values="0 0 0 0 0  0 0 0 0 0  0 0 0 0 0  0 0 0 .75 0" result="drop"/>
  <feMerge><feMergeNode in="drop"/><feMergeNode in="SourceGraphic"/><feMergeNode in="shine"/></feMerge>
</filter>
<filter id="soft"><feGaussianBlur stdDeviation="9"/></filter>
<filter id="softer"><feGaussianBlur stdDeviation="20"/></filter>
<filter id="tiny"><feGaussianBlur stdDeviation="2.2"/></filter>

<linearGradient id="brass" x1="0" x2="1" y1="0" y2="0">
  <stop offset="0" stop-color="#2c1b09"/><stop offset=".14" stop-color="#7c5522"/><stop offset=".3" stop-color="#e0b566"/>
  <stop offset=".42" stop-color="#b98a40"/><stop offset=".72" stop-color="#6a4519"/><stop offset="1" stop-color="#1c1105"/>
</linearGradient>
<linearGradient id="brassRim" x1="0" x2="1" y1="0" y2="0">
  <stop offset="0" stop-color="#6d4b1d"/><stop offset=".28" stop-color="#ffe0a0"/><stop offset=".55" stop-color="#b58a42"/><stop offset="1" stop-color="#3a260e"/>
</linearGradient>
<radialGradient id="bronze" cx=".36" cy=".32" r=".8">
  <stop offset="0" stop-color="#e4b76b"/><stop offset=".35" stop-color="#a97732"/><stop offset=".75" stop-color="#5e3c15"/><stop offset="1" stop-color="#2a1a08"/>
</radialGradient>
<linearGradient id="wax" x1="0" x2="1" y1="0" y2="0">
  <stop offset="0" stop-color="#8a6f49"/><stop offset=".2" stop-color="#e9d8b4"/><stop offset=".42" stop-color="#fff6df"/>
  <stop offset=".75" stop-color="#cdb288"/><stop offset="1" stop-color="#5f4a2f"/>
</linearGradient>
<linearGradient id="wood" x1="0" x2="1" y1="0" y2="0">
  <stop offset="0" stop-color="#1e1007"/><stop offset=".35" stop-color="#6b4022"/><stop offset=".6" stop-color="#4d2c15"/><stop offset="1" stop-color="#160b04"/>
</linearGradient>
<linearGradient id="petal" x1="0" x2="0" y1="0" y2="1">
  <stop offset="0" stop-color="#d9709a"/><stop offset=".4" stop-color="#f3b7cb"/><stop offset="1" stop-color="#fff0ee"/>
</linearGradient>
<linearGradient id="petalDark" x1="0" x2="0" y1="0" y2="1">
  <stop offset="0" stop-color="#a84a74"/><stop offset=".55" stop-color="#d98aa9"/><stop offset="1" stop-color="#f1cdd2"/>
</linearGradient>
<linearGradient id="leaf" x1=".1" x2=".9" y1=".05" y2=".95">
  <stop offset="0" stop-color="#a9b25a"/><stop offset=".45" stop-color="#5f7f39"/><stop offset="1" stop-color="#22391c"/>
</linearGradient>
<radialGradient id="basalt" cx=".34" cy=".26" r=".85">
  <stop offset="0" stop-color="#a8a59e"/><stop offset=".45" stop-color="#5f5d58"/><stop offset="1" stop-color="#161513"/>
</radialGradient>
<radialGradient id="gilt" cx=".36" cy=".3" r=".85">
  <stop offset="0" stop-color="#fff0c0"/><stop offset=".45" stop-color="#d9ab58"/><stop offset="1" stop-color="#7a5320"/>
</radialGradient>
<radialGradient id="medal" cx=".36" cy=".3" r=".85">
  <stop offset="0" stop-color="#4e3a24"/><stop offset=".6" stop-color="#2a1d11"/><stop offset="1" stop-color="#0e0906"/>
</radialGradient>`;

const shadow = (cx, cy, rx, ry, a = 0.6) =>
  `<ellipse cx="${cx}" cy="${cy}" rx="${rx}" ry="${ry}" fill="#000" opacity="${a}" filter="url(#soft)"/>`;

const medallion = (mark) => `
  ${shadow(262, 268, 214, 214, 0.55)}
  <circle cx="256" cy="256" r="224" fill="url(#bronze)" filter="url(#hammered)"/>
  <circle cx="256" cy="256" r="196" fill="url(#medal)" filter="url(#fine)"/>
  <circle cx="256" cy="256" r="210" fill="none" stroke="#e8c27c" stroke-opacity=".35" stroke-width="2"/>
  <g filter="url(#emboss)" fill="url(#gilt)" stroke="none">${mark}</g>`;

const petal = (angle, len, width, fill, cx = 256, cy = 380) => `
  <path transform="rotate(${angle} ${cx} ${cy})" fill="url(#${fill})" stroke="#8c3f60" stroke-opacity=".28" stroke-width="1.5"
    d="M${cx} ${cy} C ${cx - width} ${cy - len * 0.34}, ${cx - width * 0.8} ${cy - len * 0.72}, ${cx} ${cy - len}
       C ${cx + width * 0.8} ${cy - len * 0.72}, ${cx + width} ${cy - len * 0.34}, ${cx} ${cy} Z"/>
  <path transform="rotate(${angle} ${cx} ${cy})" fill="none" stroke="#fff" stroke-opacity=".28" stroke-width="1.6"
    d="M${cx} ${cy - 18} L${cx} ${cy - len * 0.92}"/>`;

const veins = () => {
  let out = "";
  for (const [y, reach] of [[150, 58], [195, 92], [240, 118], [285, 128], [330, 116], [372, 74]]) {
    out += `<path d="M256 ${y} Q ${256 - reach * 0.55} ${y - 8} ${256 - reach} ${y - 38}"/><path d="M256 ${y} Q ${256 + reach * 0.55} ${y - 8} ${256 + reach} ${y - 38}"/>`;
  }
  return out;
};

const ELEMENTS = {
  bowl: `
    ${shadow(262, 412, 186, 30, 0.75)}
    <path d="M58 212 C 62 330, 146 414, 256 414 C 366 414, 450 330, 454 212 Z" fill="url(#brass)" filter="url(#hammered)"/>
    <path d="M100 262 C 124 340, 180 386, 240 396" fill="none" stroke="#ffe7b4" stroke-opacity=".42" stroke-width="9" filter="url(#tiny)"/>
    <path d="M78 286 C 170 330, 342 330, 434 286" fill="none" stroke="#2a1a08" stroke-opacity=".5" stroke-width="2.5"/>
    <path d="M86 304 C 176 348, 336 348, 426 304" fill="none" stroke="#f2cf8d" stroke-opacity=".26" stroke-width="2"/>
    <ellipse cx="256" cy="212" rx="198" ry="46" fill="url(#brassRim)"/>
    <ellipse cx="256" cy="214" rx="180" ry="36" fill="#1b1006"/>
    <path d="M86 216 A 170 32 0 0 0 426 216 A 180 40 0 0 1 86 216 Z" fill="#a67a38" opacity=".75"/>
    <ellipse cx="200" cy="228" rx="70" ry="9" fill="#d9ab60" opacity=".22" filter="url(#tiny)"/>`,

  gong: `
    ${shadow(256, 488, 196, 16, 0.7)}
    <rect x="62" y="70" width="28" height="418" rx="5" fill="url(#wood)" filter="url(#grain)"/>
    <rect x="422" y="70" width="28" height="418" rx="5" fill="url(#wood)" filter="url(#grain)"/>
    <rect x="36" y="44" width="440" height="34" rx="7" fill="#4b2c16" filter="url(#grain)"/>
    <rect x="36" y="44" width="440" height="5" fill="#a97c4c" opacity=".5"/>
    <path d="M190 78 L216 136 M322 78 L296 136" stroke="#1b1208" stroke-width="5"/>
    ${shadow(262, 302, 158, 158, 0.55)}
    <circle cx="256" cy="288" r="160" fill="url(#bronze)" filter="url(#hammered)"/>
    <circle cx="256" cy="288" r="148" fill="none" stroke="#2a1a08" stroke-opacity=".55" stroke-width="5"/>
    <circle cx="256" cy="288" r="142" fill="none" stroke="#f0cc8c" stroke-opacity=".3" stroke-width="2"/>
    <circle cx="256" cy="288" r="92" fill="none" stroke="#2a1a08" stroke-opacity=".3" stroke-width="3"/>
    <circle cx="256" cy="288" r="56" fill="url(#gilt)" filter="url(#hammered)"/>
    <circle cx="256" cy="288" r="56" fill="none" stroke="#2a1a08" stroke-opacity=".6" stroke-width="3"/>
    <path d="M150 210 A 130 130 0 0 1 228 164" fill="none" stroke="#fff0c8" stroke-opacity=".4" stroke-width="9" filter="url(#tiny)"/>`,

  candle: `
    ${shadow(260, 466, 150, 22, 0.75)}
    <ellipse cx="256" cy="446" rx="146" ry="30" fill="url(#brass)" filter="url(#hammered)"/>
    <ellipse cx="256" cy="438" rx="146" ry="26" fill="url(#brassRim)"/>
    <ellipse cx="256" cy="438" rx="118" ry="17" fill="#3c2710"/>
    <rect x="186" y="186" width="140" height="254" fill="url(#wax)" filter="url(#fine)"/>
    <ellipse cx="256" cy="440" rx="70" ry="13" fill="url(#wax)"/>
    <ellipse cx="256" cy="186" rx="70" ry="15" fill="#f6e6c2"/>
    <ellipse cx="256" cy="188" rx="48" ry="9" fill="#e9b75e"/>
    <path d="M188 190 C 186 226, 200 232, 202 262 C 203 280, 214 280, 215 260 C 216 232, 226 226, 226 196 Z" fill="#fff4da" opacity=".9"/>
    <path d="M300 196 C 300 240, 311 246, 312 312 C 313 328, 324 326, 324 306 L 325 190 Z" fill="#e0c79c" opacity=".85"/>
    <path d="M256 188 L256 164" stroke="#160d06" stroke-width="5" stroke-linecap="round"/>`,

  incense: `
    ${shadow(262, 462, 164, 22, 0.75)}
    <line x1="254" y1="420" x2="254" y2="150" stroke="#1d0d05" stroke-width="10" stroke-linecap="round"/>
    <line x1="252" y1="420" x2="252" y2="150" stroke="#8c4a26" stroke-width="3"/>
    <line x1="254" y1="170" x2="254" y2="150" stroke="#76746e" stroke-width="10" stroke-linecap="round"/>
    <path d="M92 408 C 110 452, 176 462, 256 462 C 336 462, 402 452, 420 408 Z" fill="url(#brass)" filter="url(#hammered)"/>
    <ellipse cx="256" cy="408" rx="164" ry="30" fill="url(#brassRim)"/>
    <ellipse cx="256" cy="410" rx="144" ry="21" fill="#2a1a0b"/>
    <ellipse cx="256" cy="416" rx="118" ry="13" fill="#6e6a62" filter="url(#fine)"/>
    <circle cx="254" cy="414" r="17" fill="url(#gilt)" filter="url(#emboss)"/>
    <line x1="254" y1="420" x2="254" y2="380" stroke="#1d0d05" stroke-width="10" stroke-linecap="round"/>`,

  flower: `
    ${shadow(256, 414, 190, 24, 0.7)}
    <ellipse cx="256" cy="400" rx="196" ry="30" fill="#2f5a34" filter="url(#fine)"/>
    <ellipse cx="256" cy="396" rx="186" ry="24" fill="#487a45" filter="url(#fine)"/>
    ${[-62, -40, -20, 0, 20, 40, 62].map((a) => petal(a, 256, 66, "petalDark")).join("")}
    ${[-82, 82, -50, 50, -26, 26, 0].map((a) => petal(a, 224, 66, "petal", 256, 392)).join("")}
    ${[-98, 98, -64, 64, -32, 32].map((a) => petal(a, 176, 64, "petal", 256, 402)).join("")}
    ${[-46, 46, -16, 16].map((a) => petal(a, 118, 52, "petal", 256, 410)).join("")}`,

  leaf: `
    <g transform="rotate(-14 256 256)">
      <path d="M262 28 C 238 86, 92 132, 92 256 C 92 354, 196 392, 256 486 C 316 392, 420 354, 420 256 C 420 132, 274 86, 262 28 Z"
            transform="translate(10 16)" fill="#000" opacity=".55" filter="url(#soft)"/>
      <path d="M256 26 C 232 86, 86 132, 86 256 C 86 354, 190 392, 256 488 C 322 392, 426 354, 426 256 C 426 132, 280 86, 256 26 Z"
            fill="url(#leaf)" filter="url(#fine)"/>
      <path d="M256 26 C 232 86, 86 132, 86 256 C 86 300, 106 334, 136 362 C 110 258, 170 136, 256 76 Z" fill="#e7dd8a" opacity=".16"/>
      <g fill="none" stroke="#dcd98f" stroke-opacity=".5" stroke-width="2.6" transform="translate(0 74) scale(1 0.92)">${veins()}</g>
      <path d="M256 46 L256 498" stroke="#e4e09a" stroke-opacity=".62" stroke-width="5" stroke-linecap="round"/>
    </g>`,

  stone: `
    ${shadow(256, 462, 196, 26, 0.8)}
    <path d="M56 386 C 58 322, 158 298, 262 300 C 368 302, 460 326, 456 392 C 452 448, 362 464, 256 464 C 150 464, 54 446, 56 386 Z" fill="url(#basalt)" filter="url(#rock)"/>
    <ellipse cx="256" cy="306" rx="118" ry="14" fill="#000" opacity=".5" filter="url(#tiny)"/>
    <path d="M122 254 C 124 200, 198 178, 266 182 C 340 186, 396 208, 392 258 C 388 300, 322 312, 254 310 C 186 308, 120 298, 122 254 Z" fill="url(#basalt)" filter="url(#rock)"/>
    <ellipse cx="258" cy="186" rx="78" ry="10" fill="#000" opacity=".5" filter="url(#tiny)"/>
    <path d="M176 136 C 178 96, 224 78, 264 80 C 310 82, 340 100, 338 138 C 336 172, 298 186, 256 186 C 214 186, 174 170, 176 136 Z" fill="url(#basalt)" filter="url(#rock)"/>`,
};

const SYMBOLS = {
  yin_yang: `<path fill-rule="evenodd" d="M256 106 a150 150 0 1 0 0 300 a150 150 0 1 0 0 -300 Z
      M256 124 a132 132 0 0 1 0 264 a66 66 0 0 1 0 -132 a66 66 0 0 0 0 -132 Z"/>
    <circle cx="256" cy="190" r="20"/><circle cx="256" cy="322" r="20" fill="#120c07"/>`,
  enso: `<path fill="none" stroke="url(#gilt)" stroke-width="30" stroke-linecap="round" d="M296 116 A 146 146 0 1 0 392 208"/>`,
  om: `<text x="256" y="348" font-size="250" text-anchor="middle" font-family="'Noto Sans Devanagari','Noto Serif Devanagari','Lohit Devanagari',serif">ॐ</text>`,
  wheel: `<g fill="none" stroke="#dcb05e" stroke-width="16">
      <circle cx="256" cy="256" r="138"/><circle cx="256" cy="256" r="34"/>
      ${Array.from({ length: 8 }, (_, i) => `<line x1="256" y1="222" x2="256" y2="118" transform="rotate(${i * 45} 256 256)"/>`).join("")}
    </g><circle cx="256" cy="256" r="12"/>`,
  cross: `<path d="M238 96 h36 v92 h78 v36 h-78 v192 h-36 v-192 h-78 v-36 h78 Z"/>`,
};

const svg = (body, w = 512, h = 512) =>
  `<svg xmlns="http://www.w3.org/2000/svg" width="${w}" height="${h}" viewBox="0 0 ${w} ${h}"><defs>${DEFS}</defs>${body}</svg>`;

const W = 1080, H = 2340;
const wall = (top, mid, bottom, filter, seed, freq) => `
  <filter id="tex" x="0" y="0" width="100%" height="100%">
    <feTurbulence type="fractalNoise" baseFrequency="${freq}" numOctaves="5" seed="${seed}"/>
    <feDiffuseLighting surfaceScale="${filter}" lighting-color="#fff" result="l"><feDistantLight azimuth="240" elevation="60"/></feDiffuseLighting>
    <feBlend in="SourceGraphic" in2="l" mode="multiply"/>
  </filter>
  <linearGradient id="bg" x1="0" x2="0" y1="0" y2="1"><stop offset="0" stop-color="${top}"/><stop offset=".55" stop-color="${mid}"/><stop offset="1" stop-color="${bottom}"/></linearGradient>
  <radialGradient id="pool" cx=".5" cy=".42" r=".62"><stop offset="0" stop-color="#ffcf8a" stop-opacity=".16"/><stop offset="1" stop-color="#ffcf8a" stop-opacity="0"/></radialGradient>
  <radialGradient id="vignette" cx=".5" cy=".45" r=".78"><stop offset=".45" stop-color="#000" stop-opacity="0"/><stop offset="1" stop-color="#000" stop-opacity=".72"/></radialGradient>
  <rect width="${W}" height="${H}" fill="url(#bg)" filter="url(#tex)"/>
  <rect width="${W}" height="${H}" fill="url(#pool)"/>
  <rect width="${W}" height="${H}" fill="url(#vignette)"/>`;

const planks = () => {
  let out = "";
  for (let i = 1; i < 6; i++) out += `<rect x="${i * 180 - 2}" y="0" width="4" height="${H}" fill="#000" opacity=".45"/><rect x="${i * 180 + 2}" y="0" width="2" height="${H}" fill="#c99a6a" opacity=".06"/>`;
  return out;
};

const BACKDROPS = {
  night: wall("#0c0b0d", "#17141a", "#0b0a0a", 0.5, 7, "0.03"),
  dawn: wall("#1c1630", "#5c3540", "#b06a3e", 0.5, 5, "0.03"),
  stone: wall("#3a3b3d", "#4c4c4c", "#2a2a2b", 3.2, 21, "0.018"),
  wood: wall("#3a2212", "#4e2e18", "#27160b", 3.4, 3, "0.004 0.11") + planks(),
};

async function main() {
  fs.mkdirSync(OUT, { recursive: true });
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: W, height: H } });

  async function render(name, markup, w, h, alpha, quality) {
    await page.setViewportSize({ width: w, height: h });
    await page.setContent(`<!doctype html><html style="background:transparent"><body style="margin:0;background:transparent">${markup}</body></html>`);
    await page.evaluate(() => document.fonts.ready);
    const png = await page.screenshot({ omitBackground: alpha, type: "png" });
    const dataUrl = await page.evaluate(async ({ b64, w, h, quality }) => {
      const image = new Image();
      image.src = "data:image/png;base64," + b64;
      await image.decode();
      const canvas = document.createElement("canvas");
      canvas.width = w;
      canvas.height = h;
      canvas.getContext("2d").drawImage(image, 0, 0);
      return canvas.toDataURL("image/webp", quality);
    }, { b64: png.toString("base64"), w, h, quality });
    fs.writeFileSync(path.join(OUT, `${name}.webp`), Buffer.from(dataUrl.split(",")[1], "base64"));
  }

  for (const [name, body] of Object.entries(ELEMENTS)) await render(`altar_${name}`, svg(body), 512, 512, true, 0.9);
  for (const [name, mark] of Object.entries(SYMBOLS)) await render(`altar_symbol_${name}`, svg(medallion(mark)), 512, 512, true, 0.9);
  for (const [name, body] of Object.entries(BACKDROPS)) await render(`altar_bg_${name}`, svg(body, W, H), W, H, false, 0.97);
  await browser.close();
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});
