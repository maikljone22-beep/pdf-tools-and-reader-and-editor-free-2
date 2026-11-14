import React from 'react';
import {AbsoluteFill, Easing, Img, OffthreadVideo, Sequence, interpolate, spring, staticFile, useCurrentFrame, useVideoConfig} from 'remotion';
import {loadFont} from '@remotion/google-fonts/Cairo';
import footage from './footage.json';

// The SmartDocs promo: 60 s, vertical. Motion graphics around real recordings of the app (public/footage, recorded
// by the "capture" job). Cuts fall on a 120 BPM grid (one bar = 2 s = 60 frames), so a 120 BPM track fits.
export const FPS = 30;
export const TOTAL = 60 * FPS;

const {fontFamily} = loadFont('normal', {weights: ['500', '700', '900'], subsets: ['arabic', 'latin']});
const F = footage as {files: Record<string, number>; durations: Record<string, number>};
const C = {navy: '#061737', deep: '#0B2A66', blue: '#1565C0', sky: '#58A6FF', amber: '#FFB300', teal: '#00C9A7', white: '#FFFFFF', ink: '#0A1020'};
const clamp = {extrapolateLeft: 'clamp', extrapolateRight: 'clamp'} as const;

const useAppear = (delay = 0, damping = 14) => {
  const frame = useCurrentFrame();
  const {fps} = useVideoConfig();
  return spring({frame: frame - delay, fps, config: {damping, mass: 0.8}});
};

// ------------------------------------------------------------------------------------------------ backdrop

const Background: React.FC = () => {
  const f = useCurrentFrame();
  const blob = (color: string, size: number, x: number, y: number, speed: number, phase: number) => (
    <div
      style={{
        position: 'absolute', width: size, height: size, borderRadius: '50%', left: x, top: y,
        background: `radial-gradient(circle, ${color} 0%, rgba(0,0,0,0) 68%)`,
        transform: `translate(${Math.sin(f / speed + phase) * 90}px, ${Math.cos(f / (speed * 1.3) + phase) * 120}px)`,
      }}
    />
  );
  return (
    <AbsoluteFill style={{background: `linear-gradient(165deg, ${C.navy} 0%, ${C.deep} 55%, #0E3C8C 100%)`}}>
      {blob('rgba(88,166,255,0.55)', 1100, -350, -250, 70, 0)}
      {blob('rgba(0,201,167,0.38)', 900, 420, 1150, 85, 2)}
      {blob('rgba(255,179,0,0.22)', 700, 520, 330, 60, 4)}
      <AbsoluteFill
        style={{
          opacity: 0.07,
          backgroundImage: 'linear-gradient(rgba(255,255,255,0.9) 2px, transparent 2px), linear-gradient(90deg, rgba(255,255,255,0.9) 2px, transparent 2px)',
          backgroundSize: '90px 90px', backgroundPosition: `0px ${-(f * 1.2) % 90}px`,
        }}
      />
    </AbsoluteFill>
  );
};

/** A band of light that sweeps across the frame at every cut. */
const Wipes: React.FC<{at: number[]}> = ({at}) => {
  const f = useCurrentFrame();
  return (
    <>
      {at.map((b) => {
        if (f < b - 10 || f > b + 12) return null;
        const x = interpolate(f, [b - 10, b + 12], [-160, 160], {...clamp, easing: Easing.inOut(Easing.cubic)});
        return (
          <AbsoluteFill key={b} style={{transform: `translateX(${x}%) skewX(-18deg)`, background: `linear-gradient(90deg, rgba(255,179,0,0) 0%, ${C.amber} 35%, ${C.sky} 65%, rgba(88,166,255,0) 100%)`, opacity: 0.9}} />
        );
      })}
    </>
  );
};

const Scene: React.FC<{dur: number; children: React.ReactNode}> = ({dur, children}) => {
  const f = useCurrentFrame();
  const opacity = interpolate(f, [0, 8, dur - 8, dur], [0, 1, 1, 0], clamp);
  const y = interpolate(f, [dur - 12, dur], [0, -70], {...clamp, easing: Easing.in(Easing.cubic)});
  return <AbsoluteFill style={{opacity, transform: `translateY(${y}px)`}}>{children}</AbsoluteFill>;
};

// ------------------------------------------------------------------------------------------------ type

/** A headline that arrives word by word. A word that starts with * takes the accent colour. */
const Headline: React.FC<{text: string; delay?: number; size?: number; top?: number; accent?: string}> = ({text, delay = 0, size = 84, top = 140, accent = C.amber}) => {
  const frame = useCurrentFrame();
  const {fps} = useVideoConfig();
  return (
    <div style={{position: 'absolute', top, left: 60, right: 60, direction: 'rtl', textAlign: 'center', fontFamily, fontWeight: 900, fontSize: size, lineHeight: 1.3, color: C.white, textShadow: '0 6px 30px rgba(0,0,0,0.35)'}}>
      {text.split(' ').map((w, i) => {
        const s = spring({frame: frame - delay - i * 3, fps, config: {damping: 13, mass: 0.7}});
        return (
          <span key={i} style={{display: 'inline-block', margin: '0 0.13em', opacity: Math.min(1, s * 1.4), transform: `translateY(${interpolate(s, [0, 1], [56, 0])}px) scale(${interpolate(s, [0, 1], [0.86, 1])})`, color: w.startsWith('*') ? accent : undefined}}>
            {w.replace(/^\*/, '')}
          </span>
        );
      })}
    </div>
  );
};

const Sub: React.FC<{text: string; delay?: number; top: number; ltr?: boolean; size?: number}> = ({text, delay = 0, top, ltr = false, size = 44}) => {
  const s = useAppear(delay, 18);
  return (
    <div style={{position: 'absolute', top, left: 70, right: 70, direction: ltr ? 'ltr' : 'rtl', textAlign: 'center', fontFamily, fontWeight: 500, fontSize: size, color: 'rgba(255,255,255,0.86)', opacity: s, transform: `translateY(${interpolate(s, [0, 1], [26, 0])}px)`}}>
      {text}
    </div>
  );
};

/** A label that pops in beside the phone. */
const Chip: React.FC<{text: string; side: 'left' | 'right'; top: number; delay: number; color?: string; size?: number}> = ({text, side, top, delay, color = C.teal, size = 36}) => {
  const f = useCurrentFrame();
  const s = useAppear(delay, 12);
  const dir = side === 'left' ? -1 : 1;
  const float = Math.sin((f + delay * 7) / 22) * 6;
  return (
    <div
      style={{
        position: 'absolute', top: top + float, [side]: 34, direction: 'rtl', display: 'flex', alignItems: 'center', gap: 16,
        padding: '18px 30px', borderRadius: 999, background: 'rgba(255,255,255,0.97)', color: C.ink, fontFamily, fontWeight: 700, fontSize: size,
        boxShadow: '0 18px 50px rgba(0,0,0,0.35)', opacity: Math.min(1, s * 1.5), transform: `translateX(${interpolate(s, [0, 1], [dir * 220, 0])}px) scale(${interpolate(s, [0, 1], [0.7, 1])})`,
      }}
    >
      <span style={{width: 22, height: 22, borderRadius: '50%', background: color, display: 'inline-block', flexShrink: 0}} />
      {text}
    </div>
  );
};

// ------------------------------------------------------------------------------------------------ the phone and its footage

const has = (name?: string) => !!name && (F.files[name] ?? 0) > (name.endsWith('.mp4') ? 50_000 : 4_000);

/** A recording of the app (never played past its end), or a screenshot with a slow push-in when there is none. */
const Media: React.FC<{video?: string; image?: string; startFrom?: number; rate?: number}> = ({video, image, startFrom = 0, rate = 1}) => {
  const f = useCurrentFrame();
  const fill: React.CSSProperties = {width: '100%', height: '100%', objectFit: 'cover'};
  const dur = video ? F.durations[video] : undefined;
  const t = startFrom / FPS + (f / FPS) * rate;
  if (has(video) && dur !== undefined && t < dur - 0.35) {
    return <OffthreadVideo src={staticFile(`footage/${video}`)} startFrom={startFrom} playbackRate={rate} muted style={fill} />;
  }
  if (has(image)) {
    return <Img src={staticFile(`footage/${image}`)} style={{...fill, transform: `scale(${interpolate(f, [0, 240], [1, 1.07], clamp)})`, transformOrigin: '50% 28%'}} />;
  }
  return <AbsoluteFill style={{background: 'linear-gradient(160deg, #E8F0FE, #FFFFFF)'}} />;
};

const PHONE_W = 610;
const PHONE_H = Math.round((PHONE_W - 28) * (2400 / 1080)) + 28;

const Phone: React.FC<{children: React.ReactNode; enter?: number; top?: number; tilt?: number}> = ({children, enter = 6, top = 440, tilt = -5}) => {
  const f = useCurrentFrame();
  const s = useAppear(enter, 17);
  const y = interpolate(s, [0, 1], [1100, 0]) + Math.sin(f / 34) * 8;
  const ry = interpolate(s, [0, 1], [tilt * 4, tilt]) + Math.sin(f / 50) * 1.2;
  return (
    <div style={{position: 'absolute', left: (1080 - PHONE_W) / 2, top, width: PHONE_W, height: PHONE_H, transform: `perspective(2200px) translateY(${y}px) rotateY(${ry}deg) rotateX(3deg)`}}>
      <div style={{position: 'absolute', inset: 0, borderRadius: 74, background: 'linear-gradient(145deg, #2A3142, #05070D)', boxShadow: '0 70px 140px rgba(0,0,0,0.55), 0 0 0 3px rgba(255,255,255,0.10) inset'}} />
      <div style={{position: 'absolute', inset: 14, borderRadius: 60, overflow: 'hidden', background: '#FFFFFF'}}>{children}</div>
      <div style={{position: 'absolute', inset: 14, borderRadius: 60, background: 'linear-gradient(125deg, rgba(255,255,255,0.16) 0%, rgba(255,255,255,0) 32%)', pointerEvents: 'none'}} />
    </div>
  );
};

/** One piece of footage after another inside the same phone, with a short cross-fade. */
const Reel: React.FC<{parts: {from: number; dur: number; video?: string; image?: string; startFrom?: number; rate?: number}[]}> = ({parts}) => (
  <>
    {parts.map((p, i) => (
      <Sequence key={i} from={p.from} durationInFrames={p.dur + 8} layout="none">
        <Fade><Media video={p.video} image={p.image} startFrom={p.startFrom} rate={p.rate} /></Fade>
      </Sequence>
    ))}
  </>
);

const Fade: React.FC<{children: React.ReactNode}> = ({children}) => {
  const f = useCurrentFrame();
  return <AbsoluteFill style={{opacity: interpolate(f, [0, 8], [0, 1], clamp)}}>{children}</AbsoluteFill>;
};

// ------------------------------------------------------------------------------------------------ brand

const AppIcon: React.FC<{size: number}> = ({size}) => (
  <svg width={size} height={size} viewBox="0 0 108 108" style={{filter: 'drop-shadow(0 30px 60px rgba(0,0,0,0.45))'}}>
    <rect width="108" height="108" rx="25" fill={C.blue} />
    <path d="M34,22h28l14,14v50a4,4 0 0,1 -4,4H34a4,4 0 0,1 -4,-4V26a4,4 0 0,1 4,-4z" fill="#FFFFFF" />
    <path d="M62,22v14h14z" fill={C.blue} />
    <path d="M40,50h26v4H40zM40,60h26v4H40zM40,70h18v4H40z" fill={C.blue} />
  </svg>
);

const FILE_CARDS = [
  {label: 'PDF', color: '#E53935', x: 110, y: 560, r: -12},
  {label: 'DOCX', color: '#1E88E5', x: 420, y: 500, r: 7},
  {label: 'XLSX', color: '#2E7D32', x: 730, y: 590, r: 14},
  {label: 'PPTX', color: '#F4511E', x: 170, y: 930, r: 9},
  {label: 'JPG', color: '#8E24AA', x: 480, y: 990, r: -8},
  {label: 'TXT', color: '#546E7A', x: 760, y: 950, r: -15},
];

/** File cards that drift in, then rush to the centre, where the app icon appears. */
const FileCards: React.FC<{gatherAt: number}> = ({gatherAt}) => {
  const f = useCurrentFrame();
  const {fps} = useVideoConfig();
  const g = interpolate(f, [gatherAt, gatherAt + 26], [0, 1], {...clamp, easing: Easing.in(Easing.cubic)});
  return (
    <>
      {FILE_CARDS.map((c, i) => {
        const s = spring({frame: f - 4 - i * 5, fps, config: {damping: 11, mass: 0.9}});
        const x = interpolate(g, [0, 1], [c.x + Math.sin((f + i * 30) / 26) * 14, 435]);
        const y = interpolate(g, [0, 1], [c.y + Math.cos((f + i * 40) / 30) * 18, 800]);
        return (
          <div key={c.label} style={{position: 'absolute', left: x, top: y, width: 210, height: 268, borderRadius: 30, background: '#FFFFFF', overflow: 'hidden', boxShadow: '0 30px 70px rgba(0,0,0,0.4)', opacity: Math.min(1, s * 1.5) * (1 - g), transform: `translateY(${interpolate(s, [0, 1], [-500, 0])}px) rotate(${c.r * (1 - g)}deg) scale(${interpolate(g, [0, 1], [1, 0.25])})`}}>
            <div style={{height: 92, background: c.color, color: '#FFFFFF', fontFamily, fontWeight: 900, fontSize: 46, display: 'flex', alignItems: 'center', justifyContent: 'center'}}>{c.label}</div>
            {[0, 1, 2, 3].map((k) => (
              <div key={k} style={{height: 14, borderRadius: 7, background: '#D9DEE8', margin: '22px 26px 0', width: k === 3 ? 90 : undefined}} />
            ))}
          </div>
        );
      })}
    </>
  );
};

const Brand: React.FC<{nameSize?: number}> = ({nameSize = 92}) => {
  const f = useCurrentFrame();
  const s = useAppear(2, 10);
  const ring = interpolate(f, [4, 44], [0.6, 2.4], clamp);
  return (
    <>
      <div style={{position: 'absolute', left: 540 - 250, top: 560, width: 500, height: 500, borderRadius: '50%', border: `6px solid ${C.sky}`, opacity: interpolate(f, [4, 44], [0.7, 0], clamp), transform: `scale(${ring})`}} />
      <div style={{position: 'absolute', left: 540 - 170, top: 640, transform: `scale(${s}) rotate(${interpolate(s, [0, 1], [-25, 0])}deg)`}}><AppIcon size={340} /></div>
      <Headline text="محرر الملفات الذكي" delay={14} top={1060} size={nameSize} />
    </>
  );
};

// ------------------------------------------------------------------------------------------------ scenes

const S = {hook: 0, brand: 180, pdf: 300, word: 660, excel: 900, slides: 1140, tools: 1380, end: 1620};

const Hook: React.FC = () => (
  <Scene dur={S.brand - S.hook + 10}>
    <Sequence from={0} durationInFrames={92} layout="none"><Scene dur={92}><Headline text="كل يوم… ملفات من كل نوع" top={170} size={88} /></Scene></Sequence>
    <Sequence from={86} layout="none"><Headline text="*تطبيق *واحد يفتحها كلها" top={170} size={92} /></Sequence>
    <FileCards gatherAt={150} />
  </Scene>
);

const BrandScene: React.FC = () => (
  <Scene dur={S.pdf - S.brand}>
    <Brand />
    <Sub text="PDF  •  Word  •  Excel  •  PowerPoint" delay={30} top={1215} ltr size={46} />
  </Scene>
);

const PdfScene: React.FC = () => (
  <Scene dur={S.word - S.pdf}>
    <Headline text="افتح أي *PDF مهما كان حجمه" top={110} size={82} />
    <Phone>
      <Reel parts={[
        {from: 0, dur: 205, video: 'pdf_scroll.mp4', image: 'pdf_page.png', startFrom: 30},
        {from: 205, dur: 78, image: 'pdf_comment_tab.png'},
        {from: 283, dur: 80, image: 'pdf_night.png'},
      ]} />
    </Phone>
    <Chip text="ملفات بمئات الميغابايت" side="right" top={640} delay={45} />
    <Chip text="تكبير وتمرير بسلاسة" side="left" top={1010} delay={105} color={C.sky} />
    <Chip text="علّق وظلّل ووقّع" side="right" top={1330} delay={212} color={C.amber} />
    <Chip text="وضع ليلي مريح للعين" side="left" top={760} delay={290} color="#7C4DFF" />
  </Scene>
);

const WordScene: React.FC = () => (
  <Scene dur={S.excel - S.word}>
    <Headline text="حرّر مستندات *Word" top={110} size={90} />
    <Sub text="سيرة ذاتية جاهزة في ثوانٍ" delay={14} top={300} />
    <Phone tilt={5}><Reel parts={[{from: 0, dur: 240, video: 'word_use.mp4', image: 'word.png', startFrom: 30}]} /></Phone>
    <Chip text="تنسيق كامل للنص" side="left" top={700} delay={50} color={C.sky} />
    <Chip text="عربي من اليمين إلى اليسار" side="right" top={1180} delay={110} />
  </Scene>
);

const Counter: React.FC<{to: number; label: string; delay: number; top: number}> = ({to, label, delay, top}) => {
  const f = useCurrentFrame();
  const s = useAppear(delay, 11);
  const n = Math.round(interpolate(f, [delay, delay + 34], [0, to], {...clamp, easing: Easing.out(Easing.cubic)}));
  return (
    <div style={{position: 'absolute', top, right: 34, padding: '14px 34px 20px', borderRadius: 40, background: C.amber, color: C.ink, fontFamily, textAlign: 'center', direction: 'rtl', boxShadow: '0 24px 60px rgba(0,0,0,0.4)', transform: `scale(${s}) rotate(${interpolate(s, [0, 1], [-14, 4])}deg)`}}>
      <div style={{fontWeight: 900, fontSize: 104, lineHeight: 1.05}}>{n}</div>
      <div style={{fontWeight: 700, fontSize: 36}}>{label}</div>
    </div>
  );
};

const ExcelScene: React.FC = () => (
  <Scene dur={S.slides - S.excel}>
    <Headline text="جداول *Excel بصيغها ودوالّها" top={110} size={80} />
    <Phone><Reel parts={[{from: 0, dur: 240, video: 'excel_use.mp4', image: 'excel.png', startFrom: 30}]} /></Phone>
    <Counter to={187} label="دالة جاهزة" delay={40} top={620} />
    <Chip text="فواتير وميزانيات" side="left" top={1150} delay={110} color={C.sky} />
  </Scene>
);

const TEMPLATES = ['سيرة ذاتية', 'فاتورة', 'خطاب رسمي', 'ورقة اختبار', 'ميزانية شهرية', 'محضر اجتماع'];

const SlidesScene: React.FC = () => (
  <Scene dur={S.tools - S.slides}>
    <Headline text="عروض تقديمية و*قوالب *جاهزة" top={110} size={80} />
    <Phone tilt={5}>
      <Reel parts={[
        {from: 0, dur: 118, video: 'slides_use.mp4', image: 'slides.png', startFrom: 30},
        {from: 118, dur: 122, video: 'office_templates.mp4', image: 'office_templates.png', startFrom: 24},
      ]} />
    </Phone>
    {TEMPLATES.map((t, i) => (
      <Chip key={t} text={t} side={i % 2 === 0 ? 'right' : 'left'} top={610 + i * 150} delay={122 + i * 9} color={[C.teal, C.sky, C.amber][i % 3]} size={34} />
    ))}
  </Scene>
);

const TOOLS = ['دمج الملفات', 'ضغط الحجم', 'تحويل الصيغ', 'التعرّف على النص العربي', 'توقيع', 'حماية بكلمة مرور'];

const ToolsScene: React.FC = () => (
  <Scene dur={S.end - S.tools}>
    <Headline text="أكثر من *50 أداة… وبحث يجدها" top={110} size={80} />
    <Phone>
      <Reel parts={[
        {from: 0, dur: 150, video: 'tools_scroll.mp4', image: 'tools.png', startFrom: 28},
        {from: 150, dur: 90, video: 'tools_search.mp4', image: 'tools_search.png', startFrom: 20},
      ]} />
    </Phone>
    {TOOLS.map((t, i) => (
      <Chip key={t} text={t} side={i % 2 === 0 ? 'left' : 'right'} top={600 + i * 150} delay={30 + i * 12} color={[C.amber, C.teal, C.sky][i % 3]} size={34} />
    ))}
  </Scene>
);

const EndScene: React.FC = () => {
  const f = useCurrentFrame();
  return (
    <AbsoluteFill style={{opacity: interpolate(f, [0, 10], [0, 1], clamp)}}>
      <Headline text="كل ملفاتك. *تطبيق *واحد." top={230} size={96} delay={6} />
      <Brand nameSize={84} />
      <Sub text="صُمّم للعربية أولًا" delay={44} top={1220} size={50} />
    </AbsoluteFill>
  );
};

export const Promo: React.FC = () => {
  const f = useCurrentFrame();
  return (
    <AbsoluteFill style={{backgroundColor: C.navy}}>
      <Background />
      <Sequence from={S.hook} durationInFrames={S.brand - S.hook + 10}><Hook /></Sequence>
      <Sequence from={S.brand} durationInFrames={S.pdf - S.brand}><BrandScene /></Sequence>
      <Sequence from={S.pdf} durationInFrames={S.word - S.pdf}><PdfScene /></Sequence>
      <Sequence from={S.word} durationInFrames={S.excel - S.word}><WordScene /></Sequence>
      <Sequence from={S.excel} durationInFrames={S.slides - S.excel}><ExcelScene /></Sequence>
      <Sequence from={S.slides} durationInFrames={S.tools - S.slides}><SlidesScene /></Sequence>
      <Sequence from={S.tools} durationInFrames={S.end - S.tools}><ToolsScene /></Sequence>
      <Sequence from={S.end}><EndScene /></Sequence>
      <Wipes at={[S.pdf, S.word, S.excel, S.slides, S.tools, S.end]} />
      <div style={{position: 'absolute', left: 0, bottom: 0, height: 10, width: `${(f / TOTAL) * 100}%`, background: `linear-gradient(90deg, ${C.teal}, ${C.amber})`}} />
    </AbsoluteFill>
  );
};
