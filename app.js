/* FaceFusion Mobile — web app (PWA). All processing on-device via face-api.js + canvas warp engine. */
'use strict';
const $ = id => document.getElementById(id);
const $$ = s => [...document.querySelectorAll(s)];
const sleep = ms => new Promise(r => setTimeout(r, ms));
const clamp = (v, a, b) => Math.max(a, Math.min(b, v));

/* ---------- run log ---------- */
const LOG = [];
function log(msg) {
  const line = `[${new Date().toLocaleTimeString()}] ${msg}`;
  LOG.push(line);
  if (LOG.length > 200) LOG.shift();
  const el = $('runLog');
  if (el) { el.textContent = LOG.join('\n'); el.scrollTop = el.scrollHeight; }
}
function toast(msg, ms = 2600) {
  const t = $('toast');
  t.textContent = msg; t.classList.add('show');
  clearTimeout(t._h); t._h = setTimeout(() => t.classList.remove('show'), ms);
}

/* ---------- i18n ---------- */
const I18N = {
  en: {
    tagline: 'Offline face swapping on your phone · No server, no upload',
    aiEngine: 'AI Engine', initializing: 'Initializing…', sourceFace: '1 · Source face',
    pickSource: '＋ Pick source photo', camera: '📷 Camera', target: '2 · Target',
    pickLips: 'Pick the lips', pickTarget: '＋ Pick target photo / video', camera2: '📷 Camera',
    processors: 'Processors', pSwapper: 'face_swapper', pEditor: 'face_editor', pLipsync: 'lip_syncer',
    pEnhancer: 'face_enhancer', procOrder: 'Run order: swapper / animator → editor → lip syncer → enhancer',
    animator: '🎭 Face animator…', voice: '🎙️ Voice…',
    outputSettings: 'Output settings', resolution: 'Resolution cap', fps: 'Frame rate',
    trimStart: 'Trim start (s)', trimEnd: 'Trim end (s, 0 = end)',
    fastVideo: 'Fast video', assignMode: 'Assign per person',
    preview: 'Preview', before: 'Before', after: 'After',
    previewHint: 'Pick a source and target — the swap preview appears here before you run.',
    swap: 'Swap faces', playLive: '▶ Play with live swap', cancel: 'Cancel',
    save: 'Save to gallery', share: 'Share',
    privacyNote: '100% private. Nothing leaves your phone. Models run in-browser via WebGL.',
    live: 'Live', startLive: 'Start live swap', flip: '⇄ Flip', mic: 'Microphone',
    liveAssign: 'Assign per person', record: 'Record', saveRec: 'Save recording',
    liveHint: 'Tap a face in the preview to assign the selected source. Front camera is mirrored like a selfie camera.',
    batch: 'Batch', batchHint: 'Queue several clips and swap them all in one run, with one source face.',
    addClips: '＋ Add clips', runBatch: 'Run batch',
    appearance: 'Appearance', theme: 'Theme', themeSystem: 'System', themeLight: 'Light', themeDark: 'Dark',
    language: 'Language', device: 'Device', gfx: 'Graphics chip', lowMem: 'Low memory',
    models: 'Models', dlSource: 'Download from', downloadModels: 'Download models',
    modelNote: 'Web build: face-api models load from CDN and are cached for offline use. The native app downloads ~420 MB of NPU models from Hugging Face with SHA256 checks.',
    remote: 'Remote API', remoteOn: 'Enable', remoteNote: 'In the native app, this serves a page on your local network so a PC browser can drive the phone. This web build already runs in any browser — share this page\u2019s link instead.',
    about: 'About', bugReport: 'Share bug report', support: 'Support', runLog: 'Run log',
    consent: 'Do not use this on real people without their consent.',
    savedFaces: 'Saved faces', savedEmpty: 'No saved faces yet. Tap 🔖 on a source to keep it.',
    swapperModel: 'Swap model', m1a: 'Default. Balanced blend.', m1b: 'Softer edges, more feather.',
    m1c: 'Stronger color match.', swapperNote: 'Web build uses a landmark-warp engine; the model pick maps to blend presets. The native app runs the real hyperswap ONNX models on the NPU.',
    blend: 'Blend', blendSmooth: 'Blend smoothness', colorMatch: 'Color match',
    faceEditor: 'Face editor', editorHint: 'Sliders are offsets: +0.5 adds that much to whatever the face is doing. All at zero costs nothing.',
    lipSyncer: 'Lip syncer', lipsyncHint: 'Redraws the mouth to match a voice you pick. Pick the voice with the 🎙️ button on the Swap screen; use 👄 to choose which faces get synced.',
    mouthGain: 'Mouth gain', lipSmooth: 'Lip smoothing',
    faceEnhancer: 'Face enhancer', enhancerHint: 'Sharpen + contrast pass over the swapped face (web build). The native app runs gpen_bfr_256.',
    sharpen: 'Sharpen', contrast: 'Contrast',
    animatorTitle: 'Face animator', animatorHint: 'A photo\u2019s face moves like the face in a driver video. Pick a driver video; the target photo animates. Never swaps.',
    pickDriver: '＋ Pick driver video', animate: 'Animate',
    voiceTitle: 'Voice (lip sync driver)', voiceHint: 'Pick an audio or video file. Play it back and trim it first, so only the part you want drives the mouth.',
    pickVoice: '＋ Pick voice file', trimStart2: 'Trim start (s)', trimEnd2: 'Trim end (s, 0 = end)',
    cameraTitle: 'Camera', capture: 'Capture', cancel2: 'Cancel',
    tabSwap: 'Swap', tabLive: 'Live', tabBatch: 'Batch', tabSettings: 'Settings',
    modelsReady: 'Models ready!', allLocal: 'All AI running locally.',
    noFaceSrc: 'No face in source.', noFaceTgt: 'No face in target.',
    done: 'Done! Tap Save to download.', processing: 'Processing…',
    saved: 'Saved faces updated.', needSource: 'Pick a source face first.',
    needTarget: 'Pick a target first.', needVoice: 'Pick a voice file first.',
    needDriver: 'Pick a driver video first.', needModels: 'Models still loading…',
    queued: 'Queued', running: 'Running…', finished: 'Finished', failed: 'Failed',
    person: 'Person', keepFace: 'Keep face', tapAssign: 'Tap a person to assign the selected source.',
    lipsOn: 'Tap faces to pick who gets lip sync. Tap again to remove.',
    liveOn: 'Live swap running.', liveOff: 'Live stopped.',
    recording: 'Recording…', recSaved: 'Recording ready.',
    copied: 'Copied to clipboard.', shared: 'Shared.',
    aboutText: 'FaceFusion Mobile (web build). A mobile port of FaceFusion by Henry Ruhs — pipeline, models and option names follow upstream. This web build runs the same workflow in your browser: face detection + landmarks (face-api.js) and a landmark-warp swap engine, fully on-device. The native Android app runs hyperswap on the Qualcomm Hexagon NPU. Built on FaceFusion (OpenRAIL-AS). Models: yoloface GPL-3.0, arcface/inswapper Non-Commercial, hyperswap ResearchRAIL. Do not use on real people without consent.',
  },
  ru: {
    tagline: 'Офлайн-замена лиц на телефоне · Без сервера и загрузки',
    aiEngine: 'ИИ-движок', initializing: 'Инициализация…', sourceFace: '1 · Исходное лицо',
    pickSource: '＋ Выбрать фото', camera: '📷 Камера', target: '2 · Цель',
    pickLips: 'Выбрать губы', pickTarget: '＋ Выбрать фото / видео', camera2: '📷 Камера',
    processors: 'Процессоры', outputSettings: 'Настройки вывода', resolution: 'Разрешение',
    fps: 'Частота кадров', trimStart: 'Начало (с)', trimEnd: 'Конец (с, 0 = конец)',
    fastVideo: 'Быстрое видео', assignMode: 'Назначить по лицам',
    preview: 'Предпросмотр', before: 'До', after: 'После',
    previewHint: 'Выберите исходник и цель — предпросмотр появится здесь.',
    swap: 'Заменить лица', playLive: '▶ Живой предпросмотр', cancel: 'Отмена',
    save: 'Сохранить в галерею', share: 'Поделиться',
    privacyNote: '100% приватно. Ничего не покидает телефон.',
    live: 'Live', startLive: 'Запустить Live', flip: '⇄ Перевернуть', mic: 'Микрофон',
    liveAssign: 'Назначить по лицам', record: 'Запись', saveRec: 'Сохранить запись',
    batch: 'Пакетная обработка', batchHint: 'Очередь клипов с одним исходным лицом.',
    addClips: '＋ Добавить клипы', runBatch: 'Запустить',
    appearance: 'Оформление', theme: 'Тема', themeSystem: 'Системная', themeLight: 'Светлая', themeDark: 'Тёмная',
    language: 'Язык', device: 'Устройство', gfx: 'Графический чип', lowMem: 'Мало памяти',
    models: 'Модели', dlSource: 'Источник загрузки', downloadModels: 'Скачать модели',
    remote: 'Удалённый API', remoteOn: 'Включить',
    about: 'О программе', bugReport: 'Отчёт об ошибке', support: 'Поддержать', runLog: 'Журнал',
    consent: 'Не используйте на реальных людях без их согласия.',
    savedFaces: 'Сохранённые лица', savedEmpty: 'Пока нет сохранённых лиц.',
    swapperModel: 'Модель замены', blend: 'Смешивание', blendSmooth: 'Плавность', colorMatch: 'Цвет',
    faceEditor: 'Редактор лица', lipSyncer: 'Синхронизация губ', faceEnhancer: 'Улучшение лица',
    animatorTitle: 'Аниматор лица', pickDriver: '＋ Выбрать видео-драйвер', animate: 'Анимировать',
    voiceTitle: 'Голос (драйвер губ)', pickVoice: '＋ Выбрать голос',
    cameraTitle: 'Камера', capture: 'Снять', cancel2: 'Отмена',
    tabSwap: 'Замена', tabLive: 'Live', tabBatch: 'Пакет', tabSettings: 'Настройки',
    modelsReady: 'Модели готовы!', allLocal: 'Весь ИИ работает локально.',
    noFaceSrc: 'Лицо не найдено в исходнике.', noFaceTgt: 'Лицо не найдено в цели.',
    done: 'Готово! Нажмите «Сохранить».', processing: 'Обработка…',
    saved: 'Сохранённые лица обновлены.', needSource: 'Сначала выберите исходное лицо.',
    needTarget: 'Сначала выберите цель.', needVoice: 'Сначала выберите голос.',
    needDriver: 'Сначала выберите видео-драйвер.', needModels: 'Модели ещё загружаются…',
    queued: 'В очереди', running: 'Выполняется…', finished: 'Готово', failed: 'Ошибка',
    person: 'Человек', keepFace: 'Оставить лицо', tapAssign: 'Нажмите на человека, чтобы назначить исходник.',
    lipsOn: 'Нажимайте на лица, чтобы выбрать синхронизацию губ.',
    liveOn: 'Live запущен.', liveOff: 'Live остановлен.',
    recording: 'Запись…', recSaved: 'Запись готова.',
    copied: 'Скопировано.', shared: 'Поделились.',
    aboutText: 'FaceFusion Mobile (веб-версия). Порт FaceFusion (Henry Ruhs) для браузера: детекция лиц и замена через варп по ландмаркам, полностью на устройстве. Нативное приложение Android запускает hyperswap на NPU Qualcomm Hexagon. Лицензия OpenRAIL-AS. Не используйте на реальных людях без согласия.',
  },
  'zh-CN': {
    tagline: '手机离线换脸 · 无服务器、无上传',
    aiEngine: 'AI 引擎', initializing: '初始化…', sourceFace: '1 · 源脸',
    pickSource: '＋ 选择源照片', camera: '📷 拍照', target: '2 · 目标',
    pickLips: '选择嘴唇', pickTarget: '＋ 选择目标照片 / 视频', camera2: '📷 拍照',
    processors: '处理器', outputSettings: '输出设置', resolution: '分辨率上限',
    fps: '帧率', trimStart: '起始（秒）', trimEnd: '结束（秒，0 = 结尾）',
    fastVideo: '快速视频', assignMode: '按人指定',
    preview: '预览', before: '之前', after: '之后',
    previewHint: '选择源脸和目标后，预览会显示在这里。',
    swap: '开始换脸', playLive: '▶ 实时预览换脸', cancel: '取消',
    save: '保存到相册', share: '分享',
    privacyNote: '100% 私密。所有处理都在本机浏览器中完成。',
    live: '实时', startLive: '开始实时换脸', flip: '⇄ 翻转', mic: '麦克风',
    liveAssign: '按人指定', record: '录制', saveRec: '保存录制',
    batch: '批量', batchHint: '将多个片段排队，用同一张源脸一次处理完。',
    addClips: '＋ 添加片段', runBatch: '运行批量',
    appearance: '外观', theme: '主题', themeSystem: '跟随系统', themeLight: '浅色', themeDark: '深色',
    language: '语言', device: '设备', gfx: '图形芯片', lowMem: '低内存',
    models: '模型', dlSource: '下载来源', downloadModels: '下载模型',
    remote: '远程 API', remoteOn: '启用',
    about: '关于', bugReport: '分享问题报告', support: '支持', runLog: '运行日志',
    consent: '未经同意，请勿对真人使用。',
    savedFaces: '已保存的脸', savedEmpty: '还没有保存的脸。',
    swapperModel: '换脸模型', blend: '融合', blendSmooth: '融合平滑', colorMatch: '颜色匹配',
    faceEditor: '脸部编辑器', lipSyncer: '唇形同步', faceEnhancer: '脸部增强',
    animatorTitle: '脸部动画', pickDriver: '＋ 选择驱动视频', animate: '生成动画',
    voiceTitle: '语音（唇同步驱动）', pickVoice: '＋ 选择语音文件',
    cameraTitle: '相机', capture: '拍摄', cancel2: '取消',
    tabSwap: '换脸', tabLive: '实时', tabBatch: '批量', tabSettings: '设置',
    modelsReady: '模型已就绪！', allLocal: '所有 AI 均在本机运行。',
    noFaceSrc: '源图中未检测到脸。', noFaceTgt: '目标图中未检测到脸。',
    done: '完成！点「保存」下载。', processing: '处理中…',
    saved: '已保存的脸已更新。', needSource: '请先选择源脸。',
    needTarget: '请先选择目标。', needVoice: '请先选择语音。',
    needDriver: '请先选择驱动视频。', needModels: '模型仍在加载…',
    queued: '排队中', running: '运行中…', finished: '已完成', failed: '失败',
    person: '人物', keepFace: '保留原脸', tapAssign: '点击人物以指定源脸。',
    lipsOn: '点击脸部选择唇同步对象，再次点击取消。',
    liveOn: '实时换脸已启动。', liveOff: '实时已停止。',
    recording: '录制中…', recSaved: '录制已就绪。',
    copied: '已复制。', shared: '已分享。',
    aboutText: 'FaceFusion Mobile（网页版）。FaceFusion（Henry Ruhs）的移动端移植：浏览器内人脸检测 + 关键点变形换脸，完全离线。原生 Android 版在高通 Hexagon NPU 上运行 hyperswap。许可证 OpenRAIL-AS。未经同意请勿对真人使用。',
  },
  'zh-TW': {
    tagline: '手機離線換臉 · 無伺服器、無上傳',
    aiEngine: 'AI 引擎', initializing: '初始化…', sourceFace: '1 · 源臉',
    pickSource: '＋ 選擇源照片', camera: '📷 拍照', target: '2 · 目標',
    pickLips: '選擇嘴唇', pickTarget: '＋ 選擇目標照片 / 影片', camera2: '📷 拍照',
    processors: '處理器', outputSettings: '輸出設定', resolution: '解析度上限',
    fps: '幀率', trimStart: '起始（秒）', trimEnd: '結束（秒，0 = 結尾）',
    fastVideo: '快速影片', assignMode: '按人指定',
    preview: '預覽', before: '之前', after: '之後',
    previewHint: '選擇源臉和目標後，預覽會顯示在這裡。',
    swap: '開始換臉', playLive: '▶ 即時預覽換臉', cancel: '取消',
    save: '儲存到相簿', share: '分享',
    privacyNote: '100% 私密。所有處理都在本機瀏覽器中完成。',
    live: '即時', startLive: '開始即時換臉', flip: '⇄ 翻轉', mic: '麥克風',
    liveAssign: '按人指定', record: '錄製', saveRec: '儲存錄製',
    batch: '批次', batchHint: '將多個片段排隊，用同一張源臉一次處理完。',
    addClips: '＋ 新增片段', runBatch: '執行批次',
    appearance: '外觀', theme: '主題', themeSystem: '跟隨系統', themeLight: '淺色', themeDark: '深色',
    language: '語言', device: '裝置', gfx: '圖形晶片', lowMem: '低記憶體',
    models: '模型', dlSource: '下載來源', downloadModels: '下載模型',
    remote: '遠端 API', remoteOn: '啟用',
    about: '關於', bugReport: '分享問題報告', support: '支援', runLog: '執行日誌',
    consent: '未經同意，請勿對真人使用。',
    savedFaces: '已儲存的臉', savedEmpty: '還沒有儲存的臉。',
    swapperModel: '換臉模型', blend: '融合', blendSmooth: '融合平滑', colorMatch: '顏色匹配',
    faceEditor: '臉部編輯器', lipSyncer: '唇形同步', faceEnhancer: '臉部增強',
    animatorTitle: '臉部動畫', pickDriver: '＋ 選擇驅動影片', animate: '產生動畫',
    voiceTitle: '語音（唇同步驅動）', pickVoice: '＋ 選擇語音檔',
    cameraTitle: '相機', capture: '拍攝', cancel2: '取消',
    tabSwap: '換臉', tabLive: '即時', tabBatch: '批次', tabSettings: '設定',
    modelsReady: '模型已就緒！', allLocal: '所有 AI 均在本機執行。',
    noFaceSrc: '源圖中未偵測到臉。', noFaceTgt: '目標圖中未偵測到臉。',
    done: '完成！點「儲存」下載。', processing: '處理中…',
    saved: '已儲存的臉已更新。', needSource: '請先選擇源臉。',
    needTarget: '請先選擇目標。', needVoice: '請先選擇語音。',
    needDriver: '請先選擇驅動影片。', needModels: '模型仍在載入…',
    queued: '排隊中', running: '執行中…', finished: '已完成', failed: '失敗',
    person: '人物', keepFace: '保留原臉', tapAssign: '點擊人物以指定源臉。',
    lipsOn: '點擊臉部選擇唇同步物件，再次點擊取消。',
    liveOn: '即時換臉已啟動。', liveOff: '即時已停止。',
    recording: '錄製中…', recSaved: '錄製已就緒。',
    copied: '已複製。', shared: '已分享。',
    aboutText: 'FaceFusion Mobile（網頁版）。FaceFusion（Henry Ruhs）的行動端移植：瀏覽器內人臉偵測 + 關鍵點變形換臉，完全離線。原生 Android 版在高通 Hexagon NPU 上執行 hyperswap。授權 OpenRAIL-AS。未經同意請勿對真人使用。',
  },
};
let lang = localStorage.getItem('ffm_lang') || 'en';
function t(k) { return (I18N[lang] && I18N[lang][k]) || I18N.en[k] || k; }
function applyLang() {
  document.documentElement.lang = lang;
  $$('[data-i18n]').forEach(el => { el.textContent = t(el.dataset.i18n); });
  $('aboutText').textContent = t('aboutText');
  $('langSel').value = lang;
  renderModelList(); renderDeviceInfo(); renderSrcRow(); renderBatchList();
}

/* ---------- settings / theme ---------- */
function applyTheme() {
  const pref = localStorage.getItem('ffm_theme') || 'system';
  const dark = pref === 'dark' || (pref === 'system' && matchMedia('(prefers-color-scheme: light)').matches === false);
  document.documentElement.dataset.theme = dark ? 'dark' : 'light';
  $('themeSel').value = pref;
}
$('themeSel').onchange = e => { localStorage.setItem('ffm_theme', e.target.value); applyTheme(); };
$('langSel').onchange = e => { lang = e.target.value; localStorage.setItem('ffm_lang', lang); applyLang(); };
$('gfxSel').onchange = e => { localStorage.setItem('ffm_gfx', e.target.value); log('Graphics chip: ' + e.target.value); };
$('lowMemToggle').onchange = e => { localStorage.setItem('ffm_lowmem', e.target.checked ? '1' : '0'); };
$('dlSourceSel').onchange = e => { localStorage.setItem('ffm_dl', e.target.value); log('Model source: ' + e.target.value); };
$('remoteToggle').onchange = e => {
  $('remoteInfo').textContent = e.target.checked
    ? location.href + '  (share this link — the web build runs in any browser)'
    : t('remoteNote');
  log('Remote API ' + (e.target.checked ? 'on' : 'off'));
};

/* ---------- state ---------- */
const S = {
  sources: [], activeSrc: -1,          // [{img, thumb, name}]
  target: null, targetKind: null,     // 'image' | 'video'
  targetFaces: [],                    // detections
  assignments: {},                    // personIdx -> sourceIdx | 'keep'
  lipsMode: false, lipsSel: new Set(),
  proc: { swapper: true, editor: false, lipsync: false, enhancer: false },
  swapperModel: '1a',
  editor: {}, voice: null, voiceEnv: null, driver: null,
  modelsReady: false, cancelled: false,
  batch: [], live: { on: false, stream: null, raf: 0, facing: 'user', mirror: true, rec: null, chunks: [] },
};
const EDITOR_PARAMS = [
  ['smile', 'Smile'], ['mouthOpen', 'Mouth open'], ['lipFull', 'Lip fullness'],
  ['eyeOpen', 'Eye open'], ['eyeSquint', 'Eye squint'], ['browRaise', 'Brow raise'],
  ['browFurrow', 'Brow furrow'], ['gazeL', 'Gaze left'], ['gazeR', 'Gaze right'],
  ['headTurn', 'Head turn'], ['headNod', 'Head nod'], ['headTilt', 'Head tilt'],
  ['cheekUp', 'Cheek up'], ['jawOpen', 'Jaw open'],
];
EDITOR_PARAMS.forEach(([k]) => S.editor[k] = 0);

/* ---------- tabs & sheets ---------- */
$$('nav.tabs button').forEach(b => b.onclick = () => {
  $$('nav.tabs button').forEach(x => x.classList.toggle('active', x === b));
  $$('.screen').forEach(s => s.classList.remove('active'));
  $('screen-' + b.dataset.tab).classList.add('active');
  if (b.dataset.tab !== 'live' && S.live.on) stopLive();
  window.scrollTo({ top: 0 });
});
function openSheet(id) { $('sheetBack').classList.add('show'); $(id).classList.add('show'); }
function closeSheets() { $('sheetBack').classList.remove('show'); $$('.sheet').forEach(s => s.classList.remove('show')); stopCameraSheet(); }
$('sheetBack').onclick = closeSheets;
$$('[data-close]').forEach(b => b.onclick = closeSheets);

/* ---------- models ---------- */
const MODEL_BASES = {
  hf: 'https://justadudewhohacks.github.io/face-api.js/models',
  mirror: 'https://cdn.jsdelivr.net/gh/justadudewhohacks/face-api.js@master/weights',
};
const MODEL_LIST = [
  ['ssd_mobilenetv1', 'Face detector (SSD)', '~5.4 MB'],
  ['face_landmark_68', '68-point landmarks', '~3.6 MB'],
  ['face_recognition', 'Face recognition', '~4.2 MB'],
  ['face_expression', 'Expression net', '~3.1 MB'],
  ['tiny_face_detector', 'Tiny detector (Live)', '~0.2 MB'],
];
function renderModelList() {
  $('modelList').innerHTML = MODEL_LIST.map(([n, d, s]) =>
    `<div class="model-row"><span class="nm">${n}<small>${d}</small></span><span class="badge ${S.modelsReady ? 'green' : 'gray'}">${S.modelsReady ? '✓' : s}</span></div>`).join('');
}
async function loadModels() {
  if (typeof faceapi === 'undefined') { $('modelStatus').textContent = 'face-api.js failed to load (check connection).'; return; }
  const base = MODEL_BASES[localStorage.getItem('ffm_dl') || 'hf'];
  const bar = $('loadBar'), fill = $('loadFill'), st = $('modelStatus');
  bar.style.display = 'block';
  const steps = [
    ['Loading SSD detector…', 15, () => faceapi.nets.ssdMobilenetv1.loadFromUri(base)],
    ['Loading landmarks…', 40, () => faceapi.nets.faceLandmark68Net.loadFromUri(base)],
    ['Loading recognition…', 65, () => faceapi.nets.faceRecognitionNet.loadFromUri(base)],
    ['Loading expression model…', 85, () => faceapi.nets.faceExpressionNet.loadFromUri(base)],
    ['Loading tiny detector (Live)…', 95, () => faceapi.nets.tinyFaceDetector.loadFromUri(base)],
  ];
  try {
    for (const [msg, pct, fn] of steps) { st.textContent = msg; fill.style.width = pct + '%'; await fn(); }
    fill.style.width = '100%';
    st.innerHTML = `<span style="color:var(--green)">${t('modelsReady')}</span> ${t('allLocal')}`;
    S.modelsReady = true; log('Models ready from ' + base);
    renderModelList(); checkReady(); refreshPreview();
  } catch (e) {
    st.textContent = 'Failed: ' + e.message; st.style.color = 'var(--red)';
    log('Model load failed: ' + e.message);
  }
}
$('dlModelsBtn').onclick = async () => { $('dlModelsBtn').disabled = true; await loadModels(); $('dlModelsBtn').disabled = false; };

/* ---------- device info ---------- */
function webglRenderer() {
  try {
    const c = document.createElement('canvas');
    const gl = c.getContext('webgl') || c.getContext('experimental-webgl');
    if (!gl) return 'none';
    const ext = gl.getExtension('WEBGL_debug_renderer_info');
    return ext ? gl.getParameter(ext.UNMASKED_RENDERER_WEBGL) : 'WebGL';
  } catch { return 'none'; }
}
function renderDeviceInfo() {
  const low = localStorage.getItem('ffm_lowmem') === '1';
  $('deviceInfo').innerHTML =
    `${navigator.platform} · ${navigator.hardwareConcurrency || '?'} cores · ${navigator.deviceMemory || '?'} GB RAM<br>` +
    `GPU: ${webglRenderer()}<br>Screen: ${screen.width}×${screen.height} · DPR ${devicePixelRatio}<br>` +
    (low ? `<span class="badge gray">Low memory on</span>` : '');
  $('deviceBadge').textContent = localStorage.getItem('ffm_gfx') === 'cpu' ? 'CPU' : 'WebGL';
  $('lowMemToggle').checked = low;
  $('gfxSel').value = localStorage.getItem('ffm_gfx') || 'auto';
  $('dlSourceSel').value = localStorage.getItem('ffm_dl') || 'hf';
}

/* ---------- image helpers ---------- */
function fileToImage(file, maxDim = 1536) {
  return new Promise((res, rej) => {
    const r = new FileReader();
    r.onload = e => {
      const img = new Image();
      img.onload = () => {
        let w = img.width, h = img.height;
        if (Math.max(w, h) > maxDim) { const s = maxDim / Math.max(w, h); w = Math.round(w * s); h = Math.round(h * s); }
        const c = document.createElement('canvas'); c.width = w; c.height = h;
        c.getContext('2d').drawImage(img, 0, 0, w, h);
        const out = new Image();
        out.onload = () => res(out);
        out.src = c.toDataURL('image/jpeg', 0.95);
      };
      img.onerror = rej; img.src = e.target.result;
    };
    r.onerror = rej; r.readAsDataURL(file);
  });
}
function cropThumb(img, box, size = 160) {
  const c = document.createElement('canvas'); c.width = c.height = size;
  const ctx = c.getContext('2d');
  const s = Math.min(box.width, box.height) * 1.1;
  ctx.drawImage(img, box.x + box.width / 2 - s / 2, box.y + box.height / 2 - s / 2, s, s, 0, 0, size, size);
  return c.toDataURL('image/jpeg', 0.8);
}

/* ---------- sources ---------- */
function renderSrcRow() {
  const row = $('srcRow');
  row.innerHTML = '';
  S.sources.forEach((s, i) => {
    const d = document.createElement('div');
    d.className = 'src-item' + (i === S.activeSrc ? ' active' : '');
    d.innerHTML = `<img src="${s.thumb}" alt=""><button class="rm" title="remove">✕</button><div class="lbl">${s.name}</div>`;
    d.onclick = () => { S.activeSrc = i; renderSrcRow(); checkReady(); refreshPreview(); };
    d.querySelector('.rm').onclick = e => {
      e.stopPropagation();
      S.sources.splice(i, 1);
      if (S.activeSrc >= S.sources.length) S.activeSrc = S.sources.length - 1;
      renderSrcRow(); checkReady(); refreshPreview();
    };
    row.appendChild(d);
  });
  const add = document.createElement('label');
  add.className = 'src-add'; add.textContent = '＋'; add.title = t('pickSource');
  add.innerHTML = '＋<input type="file" accept="image/*" id="srcFile2">';
  add.querySelector('input').onchange = e => { if (e.target.files[0]) addSource(e.target.files[0]); };
  row.appendChild(add);
  const bm = document.createElement('button');
  bm.className = 'src-add'; bm.textContent = '🔖'; bm.title = 'Save face';
  bm.onclick = saveActiveFace;
  row.appendChild(bm);
}
async function addSource(file) {
  try {
    const img = await fileToImage(file);
    S.sources.push({ img, thumb: cropThumb(img, { x: 0, y: 0, width: img.width, height: img.height }), name: file.name.slice(0, 14) });
    S.activeSrc = S.sources.length - 1;
    log('Source added: ' + file.name);
    renderSrcRow(); checkReady(); refreshPreview();
  } catch (e) { toast(e.message); }
}
$('srcFile').onchange = e => { if (e.target.files[0]) addSource(e.target.files[0]); };

/* saved faces (localStorage) */
function getSaved() { try { return JSON.parse(localStorage.getItem('ffm_saved') || '[]'); } catch { return []; } }
function saveActiveFace() {
  if (S.activeSrc < 0) { toast(t('needSource')); return; }
  const s = S.sources[S.activeSrc];
  const saved = getSaved();
  saved.unshift({ thumb: s.thumb, name: s.name, at: Date.now() });
  localStorage.setItem('ffm_saved', JSON.stringify(saved.slice(0, 24)));
  toast(t('saved')); renderSaved();
}
function renderSaved() {
  const saved = getSaved();
  $('savedEmpty').style.display = saved.length ? 'none' : 'block';
  $('savedGrid').innerHTML = '';
  saved.forEach((s, i) => {
    const d = document.createElement('div');
    d.className = 's';
    d.innerHTML = `<img src="${s.thumb}" alt=""><button class="del">✕</button>`;
    d.querySelector('img').onclick = async () => {
      const img = new Image();
      img.onload = () => {
        S.sources.push({ img, thumb: s.thumb, name: s.name });
        S.activeSrc = S.sources.length - 1;
        renderSrcRow(); checkReady(); refreshPreview(); closeSheets();
      };
      img.src = s.thumb;
    };
    d.querySelector('.del').onclick = () => {
      const arr = getSaved(); arr.splice(i, 1);
      localStorage.setItem('ffm_saved', JSON.stringify(arr)); renderSaved();
    };
    $('savedGrid').appendChild(d);
  });
}
$('savedBtn').onclick = () => { renderSaved(); openSheet('sheet-saved'); };

/* ---------- target ---------- */
async function setTarget(file) {
  const isVideo = file.type.startsWith('video/');
  if (isVideo) {
    const url = URL.createObjectURL(file);
    const v = $('tgtVideo');
    v.src = url; v.classList.remove('hidden'); $('tgtPreview').classList.add('hidden');
    $('tgtStage').classList.remove('hidden');
    await new Promise(r => { v.onloadedmetadata = r; });
    S.target = v; S.targetKind = 'video'; S.targetName = file.name;
    log('Target video: ' + file.name + ' ' + v.videoWidth + '×' + v.videoHeight);
  } else {
    const img = await fileToImage(file);
    S.target = img; S.targetKind = 'image'; S.targetName = file.name;
    $('tgtPreview').src = img.src; $('tgtPreview').classList.remove('hidden');
    $('tgtVideo').classList.add('hidden');
    $('tgtStage').classList.remove('hidden');
    log('Target image: ' + file.name + ' ' + img.width + '×' + img.height);
  }
  S.assignments = {}; S.lipsSel.clear();
  await detectTargetFaces();
  checkReady(); refreshPreview();
}
$('tgtFile').onchange = e => { if (e.target.files[0]) setTarget(e.target.files[0]); };

async function detectFaces(img) {
  return faceapi.detectAllFaces(img, new faceapi.SsdMobilenetv1Options({ minConfidence: 0.3 }))
    .withFaceLandmarks().withFaceDescriptors();
}
async function detectTargetFaces() {
  if (!S.modelsReady || !S.target) return;
  try {
    const faces = await detectFaces(S.target);
    S.targetFaces = faces;
    log(`Target faces: ${faces.length}`);
    drawFaceBoxes(); buildPersonRow();
  } catch (e) { log('Detect failed: ' + e.message); }
}
function faceBoxScale() {
  const stage = $('tgtStage');
  const el = S.targetKind === 'video' ? $('tgtVideo') : $('tgtPreview');
  const natW = S.targetKind === 'video' ? el.videoWidth : S.target.width;
  const natH = S.targetKind === 'video' ? el.videoHeight : S.target.height;
  const dispW = el.clientWidth || stage.clientWidth || natW;
  return { sx: dispW / natW, sy: dispW / natW, natW, natH };
}
function drawFaceBoxes() {
  const box = $('faceBoxes');
  box.innerHTML = '';
  if (!S.targetFaces.length) return;
  const { sx, sy } = faceBoxScale();
  S.targetFaces.forEach((f, i) => {
    const b = f.detection.box;
    const d = document.createElement('div');
    d.className = 'facebox' + (S.lipsMode ? ' pick' : '') + (S.lipsSel.has(i) ? ' lips' : '');
    d.style.cssText = `left:${b.x * sx}px;top:${b.y * sy}px;width:${b.width * sx}px;height:${b.height * sy}px`;
    d.innerHTML = `<span class="tag">${t('person')} ${i + 1}</span>`;
    if (S.lipsMode) d.onclick = () => {
      S.lipsSel.has(i) ? S.lipsSel.delete(i) : S.lipsSel.add(i);
      $('lipsCount').textContent = S.lipsSel.size ? `(${S.lipsSel.size})` : '';
      drawFaceBoxes();
    };
    box.appendChild(d);
  });
}
window.addEventListener('resize', () => drawFaceBoxes());

/* assign per person */
function buildPersonRow() {
  const row = $('personRow');
  row.innerHTML = '';
  const show = $('assignToggle').checked && S.targetFaces.length > 0;
  row.classList.toggle('hidden', !show);
  $('assignHint').textContent = show ? t('tapAssign') : '';
  if (!show) return;
  S.targetFaces.forEach((f, i) => {
    const d = document.createElement('div');
    d.className = 'person';
    const asg = S.assignments[i];
    if (asg === 'keep') d.classList.add('keep'); else if (asg !== undefined) d.classList.add('sel');
    d.innerHTML = `<img src="${cropThumb(S.target, f.detection.box)}" alt=""><div class="lbl">${t('person')} ${i + 1}</div><div class="src">${asg === 'keep' ? t('keepFace') : (asg !== undefined ? 'S' + (asg + 1) : '—')}</div>`;
    d.onclick = () => {
      const cur = S.assignments[i];
      if (cur === undefined) S.assignments[i] = S.activeSrc;
      else if (cur === S.activeSrc) S.assignments[i] = 'keep';
      else delete S.assignments[i];
      buildPersonRow(); refreshPreview();
    };
    row.appendChild(d);
  });
}
$('assignToggle').onchange = () => { buildPersonRow(); };
$('lipsBtn').onclick = () => {
  S.lipsMode = !S.lipsMode;
  $('lipsBtn').classList.toggle('ok', S.lipsMode);
  if (!S.lipsMode) { S.lipsSel.clear(); $('lipsCount').textContent = ''; }
  toast(S.lipsMode ? t('lipsOn') : '');
  drawFaceBoxes();
};

/* ---------- processor chips ---------- */
$$('#procChips .chip').forEach(chip => {
  chip.onclick = e => {
    if (e.target.dataset.gear) { openSheet('sheet-' + e.target.dataset.gear); return; }
    const p = chip.dataset.proc;
    S.proc[p] = !S.proc[p];
    chip.classList.toggle('on', S.proc[p]);
    log(`Processor ${p}: ${S.proc[p] ? 'on' : 'off'}`);
  };
});
$$('#swapperList .pick-item').forEach(it => it.onclick = () => {
  S.swapperModel = it.dataset.model;
  $$('#swapperList .pick-item').forEach(x => x.classList.toggle('sel', x === it));
  const presets = { '1a': [15, 80], '1b': [22, 70], '1c': [15, 95] };
  $('blendSlider').value = presets[it.dataset.model][0];
  $('colorSlider').value = presets[it.dataset.model][1];
  $('blendVal').textContent = presets[it.dataset.model][0];
  $('colorVal').textContent = presets[it.dataset.model][1];
  log('Swap model: hyperswap_' + it.dataset.model);
});
$$('#swapperList .pick-item')[0].classList.add('sel');
$('blendSlider').oninput = e => $('blendVal').textContent = e.target.value;
$('colorSlider').oninput = e => $('colorVal').textContent = e.target.value;
$('mouthGain').oninput = e => $('mouthGainVal').textContent = e.target.value;
$('lipSmooth').oninput = e => $('lipSmoothVal').textContent = e.target.value;
$('sharpSlider').oninput = e => $('sharpVal').textContent = e.target.value;
$('contrastSlider').oninput = e => $('contrastVal').textContent = e.target.value;

/* editor sliders */
$('editorSliders').innerHTML = EDITOR_PARAMS.map(([k, label]) =>
  `<div class="opt" style="margin-bottom:8px"><label>${label}</label><input type="range" min="-100" max="100" value="0" data-ed="${k}"><span class="val" data-edv="${k}">0</span></div>`).join('');
$$('#editorSliders input').forEach(inp => inp.oninput = () => {
  S.editor[inp.dataset.ed] = inp.value / 100;
  $(`[data-edv="${inp.dataset.ed}"]`).textContent = (inp.value / 100).toFixed(2);
});

/* ---------- engine (warp swap) ---------- */
function rgb2lab(r, g, b) {
  let rr = r / 255, gg = g / 255, bb = b / 255;
  rr = rr > 0.04045 ? Math.pow((rr + 0.055) / 1.055, 2.4) : rr / 12.92;
  gg = gg > 0.04045 ? Math.pow((gg + 0.055) / 1.055, 2.4) : gg / 12.92;
  bb = bb > 0.04045 ? Math.pow((bb + 0.055) / 1.055, 2.4) : bb / 12.92;
  let x = (rr * 0.4124564 + gg * 0.3575761 + bb * 0.1804375) / 0.95047;
  let y = (rr * 0.2126729 + gg * 0.7151522 + bb * 0.0721750) / 1.0;
  let z = (rr * 0.0193339 + gg * 0.1191920 + bb * 0.9503041) / 1.08883;
  const f = v => v > 0.008856 ? Math.cbrt(v) : 7.787 * v + 16 / 116;
  x = f(x); y = f(y); z = f(z);
  return [116 * y - 16, 500 * (x - y), 200 * (y - z)];
}
function lab2rgb(L, a, b) {
  let y = (L + 16) / 116, x = a / 500 + y, z = y - b / 200;
  const f = v => { const v3 = v * v * v; return v3 > 0.008856 ? v3 : (v - 16 / 116) / 7.787; };
  x = 0.95047 * f(x); y = 1.0 * f(y); z = 1.08883 * f(z);
  let r = x * 3.2404542 + y * -1.5371385 + z * -0.4985314;
  let g = x * -0.9692660 + y * 1.8760108 + z * 0.0415560;
  let bl = x * 0.0556434 + y * -0.2040259 + z * 1.0572252;
  const gam = c => c > 0.0031308 ? 1.055 * Math.pow(c, 1 / 2.4) - 0.055 : 12.92 * c;
  return [Math.round(clamp(gam(r) * 255, 0, 255)), Math.round(clamp(gam(g) * 255, 0, 255)), Math.round(clamp(gam(bl) * 255, 0, 255))];
}
function colorTransfer(srcData, tgtData, mask, strength) {
  const w = srcData.width, h = srcData.height;
  const sd = srcData.data, td = tgtData.data, m = mask.data;
  let sL = 0, sa = 0, sb = 0, tL = 0, ta = 0, tb = 0, cnt = 0;
  let sL2 = 0, sa2 = 0, sb2 = 0, tL2 = 0, ta2 = 0, tb2 = 0;
  for (let i = 0; i < w * h; i++) {
    if (m[i * 4] < 128) continue;
    const si = i * 4;
    const sl = rgb2lab(sd[si], sd[si + 1], sd[si + 2]);
    const tl = rgb2lab(td[si], td[si + 1], td[si + 2]);
    sL += sl[0]; sa += sl[1]; sb += sl[2]; tL += tl[0]; ta += tl[1]; tb += tl[2];
    sL2 += sl[0] * sl[0]; sa2 += sl[1] * sl[1]; sb2 += sl[2] * sl[2];
    tL2 += tl[0] * tl[0]; ta2 += tl[1] * tl[1]; tb2 += tl[2] * tl[2];
    cnt++;
  }
  if (cnt < 10) return;
  sL /= cnt; sa /= cnt; sb /= cnt; tL /= cnt; ta /= cnt; tb /= cnt;
  const sLs = Math.sqrt(sL2 / cnt - sL * sL) || 1, saS = Math.sqrt(sa2 / cnt - sa * sa) || 1, sbS = Math.sqrt(sb2 / cnt - sb * sb) || 1;
  const tLs = Math.sqrt(tL2 / cnt - tL * tL) || 1, taS = Math.sqrt(ta2 / cnt - ta * ta) || 1, tbS = Math.sqrt(tb2 / cnt - tb * tb) || 1;
  const str = strength / 100;
  for (let i = 0; i < w * h; i++) {
    if (m[i * 4] < 128) continue;
    const si = i * 4;
    let lab = rgb2lab(sd[si], sd[si + 1], sd[si + 2]);
    lab[0] = tL + (lab[0] - sL) * (tLs / sLs);
    lab[1] = ta + (lab[1] - sa) * (taS / saS);
    lab[2] = tb + (lab[2] - sb) * (tbS / sbS);
    const rgb = lab2rgb(lab[0], lab[1], lab[2]);
    sd[si] = Math.round(sd[si] * (1 - str) + rgb[0] * str);
    sd[si + 1] = Math.round(sd[si + 1] * (1 - str) + rgb[1] * str);
    sd[si + 2] = Math.round(sd[si + 2] * (1 - str) + rgb[2] * str);
  }
}
function triangulate(pts) {
  const n = pts.length; if (n < 3) return [];
  let mnx = Infinity, mny = Infinity, mxx = -Infinity, mxy = -Infinity;
  for (const p of pts) { mnx = Math.min(mnx, p.x); mny = Math.min(mny, p.y); mxx = Math.max(mxx, p.x); mxy = Math.max(mxy, p.y); }
  const dm = Math.max(mxx - mnx, mxy - mny) * 10, cx = (mnx + mxx) / 2, cy = (mny + mxy) / 2;
  const P = [...pts, { x: cx - dm, y: cy - dm }, { x: cx + dm, y: cy - dm }, { x: cx, y: cy + dm }];
  let T = [[n, n + 1, n + 2]];
  function cc(a, b, c) {
    const ax = P[a].x, ay = P[a].y, bx = P[b].x, by = P[b].y, cx2 = P[c].x, cy2 = P[c].y;
    const D = 2 * (ax * (by - cy2) + bx * (cy2 - ay) + cx2 * (ay - by));
    if (Math.abs(D) < 1e-10) return { x: 0, y: 0, r: Infinity };
    const ux = ((ax * ax + ay * ay) * (by - cy2) + (bx * bx + by * by) * (cy2 - ay) + (cx2 * cx2 + cy2 * cy2) * (ay - by)) / D;
    const uy = ((ax * ax + ay * ay) * (cx2 - bx) + (bx * bx + by * by) * (ax - cx2) + (cx2 * cx2 + cy2 * cy2) * (bx - ax)) / D;
    return { x: ux, y: uy, r: Math.hypot(ax - ux, ay - uy) };
  }
  for (let i = 0; i < n; i++) {
    const p = P[i], bad = [];
    for (let j = 0; j < T.length; j++) { const c = cc(T[j][0], T[j][1], T[j][2]); if ((p.x - c.x) ** 2 + (p.y - c.y) ** 2 < c.r * c.r) bad.push(j); }
    const edges = [];
    for (const bi of bad) {
      const tr = T[bi];
      for (let e = 0; e < 3; e++) {
        const e1 = tr[e], e2 = tr[(e + 1) % 3]; let sh = false;
        for (const bj of bad) {
          if (bj === bi) continue;
          const t2 = T[bj];
          for (let f = 0; f < 3; f++) { if ((t2[f] === e1 && t2[(f + 1) % 3] === e2) || (t2[f] === e2 && t2[(f + 1) % 3] === e1)) { sh = true; break; } }
          if (sh) break;
        }
        if (!sh) edges.push([e1, e2]);
      }
    }
    const bs = new Set(bad); T = T.filter((_, i) => !bs.has(i));
    for (const [e1, e2] of edges) T.push([e1, e2, i]);
  }
  return T.filter(tr => tr[0] < n && tr[1] < n && tr[2] < n);
}
function warpTri(sCtx, dCtx, s0, s1, s2, t0, t1, t2) {
  const det = (t1.x - t0.x) * (t2.y - t0.y) - (t2.x - t0.x) * (t1.y - t0.y);
  if (Math.abs(det) < 0.1) return;
  const a = ((s1.x - s0.x) * (t2.y - t0.y) - (s2.x - s0.x) * (t1.y - t0.y)) / det;
  const b = ((s2.x - s0.x) * (t1.x - t0.x) - (s1.x - s0.x) * (t2.x - t0.x)) / det;
  const c = ((s1.y - s0.y) * (t2.y - t0.y) - (s2.y - s0.y) * (t1.y - t0.y)) / det;
  const d = ((s2.y - s0.y) * (t1.x - t0.x) - (s1.y - s0.y) * (t2.x - t0.x)) / det;
  const e = s0.x - a * t0.x - b * t0.y, f = s0.y - c * t0.x - d * t0.y;
  dCtx.save(); dCtx.beginPath(); dCtx.moveTo(t0.x, t0.y); dCtx.lineTo(t1.x, t1.y); dCtx.lineTo(t2.x, t2.y); dCtx.closePath(); dCtx.clip();
  dCtx.setTransform(a, c, b, d, e, f); dCtx.drawImage(sCtx.canvas, 0, 0); dCtx.restore();
}
function hull(pts) {
  const s = pts.slice().sort((a, b) => a.x - b.x || a.y - b.y);
  if (s.length <= 2) return s;
  const cr = (O, A, B) => (A.x - O.x) * (B.y - O.y) - (A.y - O.y) * (B.x - O.x);
  const lo = []; for (const p of s) { while (lo.length >= 2 && cr(lo[lo.length - 2], lo[lo.length - 1], p) <= 0) lo.pop(); lo.push(p); }
  const up = []; for (let i = s.length - 1; i >= 0; i--) { const p = s[i]; while (up.length >= 2 && cr(up[up.length - 2], up[up.length - 1], p) <= 0) up.pop(); up.push(p); }
  lo.pop(); up.pop(); return lo.concat(up);
}
function distEdge(px, py, h) {
  if (!h || h.length < 3) return 999;
  let mn = Infinity;
  for (let i = 0; i < h.length; i++) {
    const a = h[i], b = h[(i + 1) % h.length];
    const dx = b.x - a.x, dy = b.y - a.y, l2 = dx * dx + dy * dy;
    let tt = l2 > 0 ? ((px - a.x) * dx + (py - a.y) * dy) / l2 : 0;
    tt = clamp(tt, 0, 1);
    const d = Math.hypot(px - (a.x + tt * dx), py - (a.y + tt * dy));
    if (d < mn) mn = d;
  }
  return mn;
}
function seamlessBlend(outData, swapData, maskData, w, h, feather, h2) {
  const od = outData.data, sd = swapData.data, md = maskData.data;
  const diff = new Float32Array(w * h * 3);
  for (let i = 0; i < w * h; i++) {
    if (md[i * 4] < 128) continue;
    const si = i * 4;
    diff[i * 3] = sd[si] - od[si]; diff[i * 3 + 1] = sd[si + 1] - od[si + 1]; diff[i * 3 + 2] = sd[si + 2] - od[si + 2];
  }
  for (let iter = 0; iter < 3; iter++) {
    const tmp = new Float32Array(diff);
    for (let y = 1; y < h - 1; y++) for (let x = 1; x < w - 1; x++) {
      const i = y * w + x;
      if (md[i * 4] < 128) continue;
      for (let c = 0; c < 3; c++) tmp[i * 3 + c] = (diff[(i - 1) * 3 + c] + diff[(i + 1) * 3 + c] + diff[(i - w) * 3 + c] + diff[(i + w) * 3 + c]) * 0.25;
    }
    diff.set(tmp);
  }
  for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
    const i = y * w + x;
    if (md[i * 4] < 1) continue;
    const edge = distEdge(x, y, h2);
    const alpha = Math.min(1, edge / Math.max(1, feather)) * (md[i * 4] / 255);
    const si = i * 4;
    od[si] = Math.round(clamp(od[si] + diff[i * 3] * alpha, 0, 255));
    od[si + 1] = Math.round(clamp(od[si + 1] + diff[i * 3 + 1] * alpha, 0, 255));
    od[si + 2] = Math.round(clamp(od[si + 2] + diff[i * 3 + 2] * alpha, 0, 255));
  }
}
const INNER_IDX = [0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,27,28,29,30,31,32,33,34,35,36,37,38,39,40,41,42,43,44,45,46,47,48,49,50,51,52,53,54,55,56,57,58,59,60,61,62,63,64,65,66,67];

/* face editor: landmark offsets (web approximation of LivePortrait sliders) */
function applyEditor(lm, ed) {
  const pts = lm.map(p => ({ x: p.x, y: p.y }));
  const cx = pts.reduce((s, p) => s + p.x, 0) / pts.length;
  const cy = pts.reduce((s, p) => s + p.y, 0) / pts.length;
  const fw = Math.max(1, Math.max(...pts.map(p => p.x)) - Math.min(...pts.map(p => p.x)));
  const fh = Math.max(1, Math.max(...pts.map(p => p.y)) - Math.min(...pts.map(p => p.y)));
  const u = fw * 0.02;
  const shift = (idx, dx, dy) => { if (pts[idx]) { pts[idx].x += dx * u; pts[idx].y += dy * u; } };
  // smile: mouth corners up/out
  shift(48, -ed.smile * 0.6, -ed.smile * 0.8); shift(54, ed.smile * 0.6, -ed.smile * 0.8);
  shift(60, 0, -ed.smile * 0.3); shift(64, 0, -ed.smile * 0.3);
  // mouth open / jaw: lower lip + chin down
  const mo = ed.mouthOpen + ed.jawOpen;
  [56,57,58,59,8,9,10,11].forEach(i => shift(i, 0, mo * 0.9));
  // lip fullness: mouth vertical scale
  [51,52,53,62,63,64,65,66,67].forEach(i => { if (pts[i]) pts[i].y += (pts[i].y - cy) * ed.lipFull * 0.15; });
  // eyes open / squint
  const eo = ed.eyeOpen - ed.eyeSquint;
  [37,38,39,40,41,42,43,44,45].forEach(i => shift(i, 0, eo * 0.5));
  // brows
  [17,18,19,20,21].forEach(i => shift(i, 0, -ed.browRaise * 0.7 + ed.browFurrow * 0.4));
  [22,23,24,25,26].forEach(i => shift(i, 0, -ed.browRaise * 0.7 + ed.browFurrow * 0.4));
  // gaze: shift eye corners
  [36,39,42,45].forEach(i => shift(i, (ed.gazeL - ed.gazeR) * 0.4, 0));
  // cheek up: under-eye up
  [31,32,33].forEach(i => shift(i, 0, -ed.cheekUp * 0.5));
  // head turn / nod / tilt
  pts.forEach(p => {
    p.x += (p.x - cx) * ed.headTurn * 0.12 + (p.y - cy) * ed.headTilt * 0.15;
    p.y += (p.y - cy) * ed.headNod * 0.12 - (p.x - cx) * ed.headTilt * 0.15;
  });
  return pts;
}
/* lip sync: scale mouth open by amount 0..1 */
function applyLipSync(pts, amt) {
  const out = pts.map(p => ({ x: p.x, y: p.y }));
  const cy = out[57] ? out[57].y : 0;
  [56,57,58,59].forEach(i => { if (out[i]) out[i].y += amt * 6; });
  [51,52,53].forEach(i => { if (out[i]) out[i].y -= amt * 3; });
  return out;
}
/* enhancer: unsharp + contrast on face region */
function enhanceRegion(ctx, w, h, box, sharp, contrast) {
  if (sharp <= 0 && contrast <= 0) return;
  const x0 = clamp(Math.floor(box.x), 0, w - 1), y0 = clamp(Math.floor(box.y), 0, h - 1);
  const x1 = clamp(Math.ceil(box.x + box.width), 1, w), y1 = clamp(Math.ceil(box.y + box.height), 1, h);
  const rw = x1 - x0, rh = y1 - y0;
  if (rw < 4 || rh < 4) return;
  const img = ctx.getImageData(x0, y0, rw, rh);
  const d = img.data;
  const src = new Uint8ClampedArray(d);
  const k = sharp / 100 * 1.2, c = 1 + contrast / 100 * 0.6;
  for (let y = 1; y < rh - 1; y++) for (let x = 1; x < rw - 1; x++) {
    const i = (y * rw + x) * 4;
    for (let ch = 0; ch < 3; ch++) {
      const blur = (src[i - rw * 4 + ch] + src[i + rw * 4 + ch] + src[i - 4 + ch] + src[i + 4 + ch] + src[i + ch] * 4) / 8;
      let v = src[i + ch] + (src[i + ch] - blur) * k;
      v = (v - 128) * c + 128;
      d[i + ch] = clamp(v, 0, 255);
    }
  }
  ctx.putImageData(img, x0, y0);
}

/* swap one face on ctx (target canvas), returns true if swapped */
function swapOneFace(outCtx, W, H, srcImg, srcLm, tgtLm, feather, colorStr, editorParams, lipAmt, doEnhance) {
  const srcC = $('c1'); srcC.width = srcImg.width; srcC.height = srcImg.height;
  const srcCtx = srcC.getContext('2d'); srcCtx.drawImage(srcImg, 0, 0);
  let tgtInner = tgtLm.map(p => ({ x: p.x, y: p.y }));
  if (editorParams) tgtInner = applyEditor(tgtInner, editorParams);
  if (lipAmt > 0) tgtInner = applyLipSync(tgtInner, lipAmt);
  const srcInner = srcLm.map(p => ({ x: p.x, y: p.y }));
  const allTgt = [...tgtInner], allSrc = [...srcInner];
  const mg = 30;
  const corners = [{ x: -mg, y: -mg }, { x: W + mg, y: -mg }, { x: W + mg, y: H + mg }, { x: -mg, y: H + mg }];
  corners.forEach(c => { allTgt.push(c); allSrc.push(c); });
  const tris = triangulate(allTgt);
  const tmpC = $('cTmp'); tmpC.width = W; tmpC.height = H;
  const tmpCtx = tmpC.getContext('2d'); tmpCtx.clearRect(0, 0, W, H);
  for (const tri of tris) {
    if (tri[0] >= INNER_IDX.length || tri[1] >= INNER_IDX.length || tri[2] >= INNER_IDX.length) continue;
    warpTri(srcCtx, tmpCtx, srcInner[tri[0]], srcInner[tri[1]], srcInner[tri[2]], tgtInner[tri[0]], tgtInner[tri[1]], tgtInner[tri[2]]);
  }
  const h2 = hull(tgtInner);
  const maskC = document.createElement('canvas'); maskC.width = W; maskC.height = H;
  const maskCtx = maskC.getContext('2d');
  const cx2 = h2.reduce((s, p) => s + p.x, 0) / h2.length;
  const cy2 = h2.reduce((s, p) => s + p.y, 0) / h2.length;
  const rad = Math.max(...h2.map(p => Math.hypot(p.x - cx2, p.y - cy2))) * 1.1;
  const grad = maskCtx.createRadialGradient(cx2, cy2, 0, cx2, cy2, rad);
  grad.addColorStop(0, 'rgba(255,255,255,1)'); grad.addColorStop(0.7, 'rgba(255,255,255,1)'); grad.addColorStop(1, 'rgba(255,255,255,0)');
  maskCtx.fillStyle = grad;
  maskCtx.beginPath(); maskCtx.moveTo(h2[0].x, h2[0].y);
  for (let i = 1; i < h2.length; i++) maskCtx.lineTo(h2[i].x, h2[i].y);
  maskCtx.closePath(); maskCtx.fill();
  if (colorStr > 0) {
    const sD = tmpCtx.getImageData(0, 0, W, H);
    const tD = outCtx.getImageData(0, 0, W, H);
    const mD = maskCtx.getImageData(0, 0, W, H);
    colorTransfer(sD, tD, mD, colorStr);
    tmpCtx.putImageData(sD, 0, 0);
  }
  const swapData = tmpCtx.getImageData(0, 0, W, H);
  const outData = outCtx.getImageData(0, 0, W, H);
  const maskData = maskCtx.getImageData(0, 0, W, H);
  seamlessBlend(outData, swapData, maskData, W, H, feather, h2);
  outCtx.putImageData(outData, 0, 0);
  if (doEnhance) {
    const xs = tgtInner.map(p => p.x), ys = tgtInner.map(p => p.y);
    enhanceRegion(outCtx, W, H, { x: Math.min(...xs), y: Math.min(...ys), width: Math.max(...xs) - Math.min(...xs), height: Math.max(...ys) - Math.min(...ys) },
      +$('sharpSlider').value, +$('contrastSlider').value);
  }
  return true;
}

/* ---------- readiness / preview ---------- */
function checkReady() {
  const ok = S.modelsReady && S.activeSrc >= 0 && S.target;
  $('swapBtn').disabled = !ok;
  $('playLiveBtn').disabled = !(ok && S.targetKind === 'video');
  $('animateBtn').disabled = !(S.modelsReady && S.target && S.targetKind === 'image' && S.driver);
}
let previewTimer = 0;
function refreshPreview() {
  clearTimeout(previewTimer);
  previewTimer = setTimeout(async () => {
    if (!S.modelsReady || S.activeSrc < 0 || !S.target) return;
    if (S.targetKind === 'video') { $('cmpBefore').src = ''; $('cmpAfter').src = ''; return; }
    try {
      $('previewHint').textContent = t('processing');
      const src = S.sources[S.activeSrc].img;
      const srcF = await detectFaces(src);
      const tgtF = S.targetFaces.length ? S.targetFaces : await detectFaces(S.target);
      if (!srcF.length || !tgtF.length) { $('previewHint').textContent = !srcF.length ? t('noFaceSrc') : t('noFaceTgt'); return; }
      const W = S.target.width, H = S.target.height;
      const outC = $('cOut'); outC.width = W; outC.height = H;
      const ctx = outC.getContext('2d'); ctx.drawImage(S.target, 0, 0);
      const count = Math.min(srcF.length, tgtF.length);
      for (let f = 0; f < count; f++) {
        const asg = S.assignments[f];
        if (asg === 'keep') continue;
        const sImg = (asg !== undefined && S.sources[asg]) ? S.sources[asg].img : src;
        const sLm = (asg !== undefined && S.sources[asg]) ? (await detectFaces(sImg))[0] : srcF[0];
        if (!sLm) continue;
        swapOneFace(ctx, W, H, sImg, sLm.landmarks.positions, tgtF[f].landmarks.positions,
          +$('blendSlider').value, +$('colorSlider').value,
          S.proc.editor ? S.editor : null, 0, S.proc.enhancer);
      }
      $('cmpBefore').src = S.target.src;
      $('cmpAfter').src = outC.toDataURL('image/jpeg', 0.9);
      $('previewHint').textContent = '';
      log('Preview rendered');
    } catch (e) { log('Preview failed: ' + e.message); }
  }, 400);
}

/* ---------- result helpers ---------- */
let resultBlob = null, resultName = '';
function showResult(blob, name) {
  resultBlob = blob; resultName = name;
  const url = URL.createObjectURL(blob);
  const isVideo = blob.type.startsWith('video/');
  $('result').classList.toggle('hidden', !isVideo);
  $('resultImg').classList.toggle('hidden', isVideo);
  if (isVideo) { $('result').src = url; $('result').classList.add('show'); }
  else { $('resultImg').src = url; $('resultImg').classList.add('show'); }
  $('saveBtn').classList.remove('hidden');
  $('shareBtn').classList.remove('hidden');
  $('status').textContent = t('done'); $('status').style.color = 'var(--green)';
}
$('saveBtn').onclick = () => {
  if (!resultBlob) return;
  const a = document.createElement('a');
  a.download = resultName || ('facefusion_' + Date.now() + (resultBlob.type.startsWith('video/') ? '.webm' : '.png'));
  a.href = URL.createObjectURL(resultBlob); a.click();
  log('Saved ' + a.download);
};
$('shareBtn').onclick = async () => {
  if (!resultBlob) return;
  try {
    const file = new File([resultBlob], resultName || 'facefusion', { type: resultBlob.type });
    if (navigator.canShare && navigator.canShare({ files: [file] })) { await navigator.share({ files: [file] }); toast(t('shared')); }
    else { await navigator.clipboard.writeText(location.href); toast(t('copied')); }
  } catch { toast(t('copied')); }
};

/* ---------- photo swap ---------- */
async function swapPhoto() {
  const st = $('status');
  S.cancelled = false; $('cancelBtn').classList.remove('hidden');
  try {
    st.textContent = t('processing'); st.style.color = 'var(--muted)';
    const src = S.sources[S.activeSrc].img;
    const srcF = await detectFaces(src);
    if (!srcF.length) { st.textContent = t('noFaceSrc'); st.style.color = 'var(--red)'; return; }
    const tgtF = S.targetFaces.length ? S.targetFaces : await detectFaces(S.target);
    if (!tgtF.length) { st.textContent = t('noFaceTgt'); st.style.color = 'var(--red)'; return; }
    const W = S.target.width, H = S.target.height;
    const outC = $('cOut'); outC.width = W; outC.height = H;
    const ctx = outC.getContext('2d'); ctx.drawImage(S.target, 0, 0);
    const count = Math.min(tgtF.length, 10);
    for (let f = 0; f < count; f++) {
      if (S.cancelled) { st.textContent = t('cancel'); return; }
      const asg = S.assignments[f];
      if (asg === 'keep') continue;
      const sImg = (asg !== undefined && S.sources[asg]) ? S.sources[asg].img : src;
      const sF = (asg !== undefined && S.sources[asg]) ? await detectFaces(sImg) : srcF;
      if (!sF.length) continue;
      const lipAmt = (S.proc.lipsync && S.voiceEnv && S.voiceEnv.length) ? S.voiceEnv[0] : 0;
      swapOneFace(ctx, W, H, sImg, sF[0].landmarks.positions, tgtF[f].landmarks.positions,
        +$('blendSlider').value, +$('colorSlider').value,
        S.proc.editor ? S.editor : null, lipAmt, S.proc.enhancer);
      st.textContent = `${t('processing')} ${f + 1}/${count}`;
      await sleep(30);
    }
    const blob = await new Promise(r => outC.toBlob(r, 'image/png'));
    showResult(blob, 'facefusion_' + Date.now() + '.png');
    log('Photo swap done');
  } catch (e) { st.textContent = e.message; st.style.color = 'var(--red)'; log('Swap failed: ' + e.message); }
  finally { $('cancelBtn').classList.add('hidden'); }
}

/* ---------- video swap ---------- */
function seekVideo(v, time) {
  return new Promise(res => {
    const on = () => { v.removeEventListener('seeked', on); res(); };
    v.addEventListener('seeked', on);
    v.currentTime = Math.min(time, Math.max(0, v.duration - 0.05));
    setTimeout(res, 1500);
  });
}
function pickMime() {
  for (const m of ['video/webm;codecs=vp9', 'video/webm;codecs=vp8', 'video/webm', 'video/mp4']) {
    if (window.MediaRecorder && MediaRecorder.isTypeSupported(m)) return m;
  }
  return '';
}
async function processVideoFrames(video, onFrame, onProgress) {
  const cap = +$('resCap').value;
  const fps = +$('fpsSel').value || Math.round(video.fps || 30) || 30;
  const t0 = +$('trimStart').value || 0;
  const t1 = (+$('trimEnd').value || 0) > 0 ? +$('trimEnd').value : video.duration;
  const scale = cap > 0 ? Math.min(1, cap / Math.max(video.videoWidth, video.videoHeight)) : 1;
  const W = Math.round(video.videoWidth * scale / 2) * 2, H = Math.round(video.videoHeight * scale / 2) * 2;
  const frames = [];
  const n = Math.max(1, Math.floor((t1 - t0) * fps));
  let prevFaces = null, prevAt = -99;
  for (let i = 0; i < n; i++) {
    if (S.cancelled) break;
    const time = t0 + i / fps;
    await seekVideo(video, time);
    const c = document.createElement('canvas'); c.width = W; c.height = H;
    const ctx = c.getContext('2d'); ctx.drawImage(video, 0, 0, W, H);
    // detection (fast video: detect every 5th frame, reuse between)
    let faces;
    if ($('fastVideo').checked && prevFaces && i - prevAt < 5) faces = prevFaces;
    else { faces = await detectFaces(c); prevFaces = faces; prevAt = i; }
    if (faces.length) {
      const src = S.sources[S.activeSrc].img;
      const srcF = await detectFaces(src);
      if (srcF.length) {
        const count = Math.min(faces.length, 10);
        for (let f = 0; f < count; f++) {
          const asg = S.assignments[f];
          if (asg === 'keep') continue;
          const lipAmt = (S.proc.lipsync && S.voiceEnv && S.voiceEnv.length)
            ? S.voiceEnv[Math.min(S.voiceEnv.length - 1, Math.floor(time * 10))] : 0;
          swapOneFace(ctx, W, H, src, srcF[0].landmarks.positions, faces[f].landmarks.positions,
            +$('blendSlider').value, +$('colorSlider').value,
            S.proc.editor ? S.editor : null, lipAmt, S.proc.enhancer);
        }
      }
    }
    frames.push(c);
    onProgress && onProgress(i + 1, n);
    await sleep(0);
  }
  return { frames, fps, W, H };
}
async function encodeFrames(frames, fps, W, H) {
  const outC = $('cOut'); outC.width = W; outC.height = H;
  const ctx = outC.getContext('2d');
  const stream = outC.captureStream(fps);
  const rec = new MediaRecorder(stream, { mimeType: pickMime(), videoBitsPerSecond: 5_000_000 });
  const chunks = [];
  rec.ondataavailable = e => e.data.size && chunks.push(e.data);
  const done = new Promise(r => rec.onstop = r);
  rec.start(200);
  for (const f of frames) { ctx.drawImage(f, 0, 0); await sleep(1000 / fps); }
  rec.stop(); await done;
  return new Blob(chunks, { type: rec.mimeType || 'video/webm' });
}
async function swapVideo() {
  const st = $('status');
  S.cancelled = false; $('cancelBtn').classList.remove('hidden');
  try {
    st.textContent = t('processing');
    const { frames, fps, W, H } = await processVideoFrames($('tgtVideo'), null, (i, n) => {
      st.textContent = `${t('processing')} ${i}/${n}`;
    });
    if (S.cancelled || !frames.length) { st.textContent = t('cancel'); return; }
    st.textContent = 'Encoding…';
    const blob = await encodeFrames(frames, fps, W, H);
    showResult(blob, 'facefusion_' + Date.now() + '.webm');
    log(`Video swap done: ${frames.length} frames @ ${fps}fps`);
  } catch (e) { st.textContent = e.message; st.style.color = 'var(--red)'; log('Video swap failed: ' + e.message); }
  finally { $('cancelBtn').classList.add('hidden'); }
}
$('swapBtn').onclick = () => {
  if (!S.modelsReady) { toast(t('needModels')); return; }
  if (S.activeSrc < 0) { toast(t('needSource')); return; }
  if (!S.target) { toast(t('needTarget')); return; }
  S.targetKind === 'video' ? swapVideo() : swapPhoto();
};
$('cancelBtn').onclick = () => { S.cancelled = true; };

/* ---------- play with live swap ---------- */
let livePlay = { on: false, raf: 0, canvas: null };
$('playLiveBtn').onclick = async () => {
  if (livePlay.on) { stopLivePlay(); return; }
  const v = $('tgtVideo');
  if (!livePlay.canvas) {
    livePlay.canvas = document.createElement('canvas');
    livePlay.canvas.style.cssText = 'width:100%;border-radius:12px;border:2px solid var(--accent);margin:10px 0;display:block';
    $('result').before(livePlay.canvas);
  }
  livePlay.canvas.classList.remove('hidden');
  livePlay.on = true; $('playLiveBtn').textContent = '⏹ Stop';
  await v.play().catch(() => {});
  const src = S.sources[S.activeSrc].img;
  const srcF = await detectFaces(src);
  const loop = async () => {
    if (!livePlay.on) return;
    const cap = +$('resCap').value || 480;
    const scale = Math.min(1, cap / Math.max(v.videoWidth, v.videoHeight));
    const W = Math.round(v.videoWidth * scale / 2) * 2, H = Math.round(v.videoHeight * scale / 2) * 2;
    const c = livePlay.canvas; c.width = W; c.height = H;
    const ctx = c.getContext('2d'); ctx.drawImage(v, 0, 0, W, H);
    if (srcF.length) {
      const faces = await detectFaces(c);
      for (let f = 0; f < Math.min(faces.length, 5); f++) {
        const asg = S.assignments[f];
        if (asg === 'keep') continue;
        swapOneFace(ctx, W, H, src, srcF[0].landmarks.positions, faces[f].landmarks.positions,
          +$('blendSlider').value, +$('colorSlider').value, null, 0, false);
      }
    }
    livePlay.raf = requestAnimationFrame(loop);
  };
  loop();
};
function stopLivePlay() {
  livePlay.on = false; cancelAnimationFrame(livePlay.raf);
  $('tgtVideo').pause();
  $('playLiveBtn').textContent = t('playLive');
}

/* ---------- animator ---------- */
$('animatorBtn').onclick = () => openSheet('sheet-animator');
$('driverFile').onchange = e => {
  if (!e.target.files[0]) return;
  const url = URL.createObjectURL(e.target.files[0]);
  $('driverPreview').src = url; $('driverPreview').classList.add('show');
  S.driver = $('driverPreview');
  checkReady();
};
$('animateBtn').onclick = async () => {
  if (!S.driver) { toast(t('needDriver')); return; }
  if (!S.target || S.targetKind !== 'image') { toast(t('needTarget')); return; }
  const st = $('status');
  S.cancelled = false; $('cancelBtn').classList.remove('hidden');
  try {
    const dv = S.driver;
    await new Promise(r => { dv.onloadedmetadata = r; });
    const fps = 24, dur = Math.min(dv.duration, 10);
    const W = S.target.width, H = S.target.height;
    const photoF = await detectFaces(S.target);
    if (!photoF.length) { st.textContent = t('noFaceTgt'); st.style.color = 'var(--red)'; return; }
    const photoLm = photoF[0].landmarks.positions;
    const frames = [];
    const n = Math.floor(dur * fps);
    for (let i = 0; i < n; i++) {
      if (S.cancelled) break;
      await seekVideo(dv, i / fps);
      const c = document.createElement('canvas'); c.width = W; c.height = H;
      const ctx = c.getContext('2d'); ctx.drawImage(S.target, 0, 0);
      const df = await detectFaces(dv);
      if (df.length) {
        const dl = df[0].landmarks.positions;
        // transfer: mouth open + eye blink + head shift (normalized deltas)
        const mouthOpen = clamp((dl[57].y - dl[51].y) / 20, 0, 1.5);
        const blink = clamp(1 - (dl[41].y - dl[37].y) / 8, 0, 1);
        let pts = photoLm.map(p => ({ x: p.x, y: p.y }));
        pts = applyLipSync(pts, mouthOpen * 0.8);
        pts.forEach(p => { p.y += blink * 2; });
        // warp photo onto itself with edited landmarks (expression transfer)
        const tmpC = $('cTmp'); tmpC.width = W; tmpC.height = H;
        const tmpCtx = tmpC.getContext('2d'); tmpCtx.drawImage(S.target, 0, 0);
        const srcC = $('c1'); srcC.width = W; srcC.height = H;
        const srcCtx = srcC.getContext('2d'); srcCtx.drawImage(S.target, 0, 0);
        const srcInner = INNER_IDX.map(i => ({ x: photoLm[i].x, y: photoLm[i].y }));
        const tgtInner = INNER_IDX.map(i => pts[i]);
        const allT = [...tgtInner], allS = [...srcInner];
        const mg = 30;
        [{ x: -mg, y: -mg }, { x: W + mg, y: -mg }, { x: W + mg, y: H + mg }, { x: -mg, y: H + mg }].forEach(cc => { allT.push(cc); allS.push(cc); });
        const tris = triangulate(allT);
        const wC = document.createElement('canvas'); wC.width = W; wC.height = H;
        const wCtx = wC.getContext('2d');
        for (const tri of tris) {
          if (tri[0] >= INNER_IDX.length || tri[1] >= INNER_IDX.length || tri[2] >= INNER_IDX.length) continue;
          warpTri(srcCtx, wCtx, srcInner[tri[0]], srcInner[tri[1]], srcInner[tri[2]], tgtInner[tri[0]], tgtInner[tri[1]], tgtInner[tri[2]]);
        }
        ctx.drawImage(wC, 0, 0);
      }
      frames.push(c);
      st.textContent = `${t('processing')} ${i + 1}/${n}`;
      await sleep(0);
    }
    if (S.cancelled || !frames.length) { st.textContent = t('cancel'); return; }
    const blob = await encodeFrames(frames, fps, W, H);
    showResult(blob, 'facefusion_anim_' + Date.now() + '.webm');
    closeSheets();
    log('Animation done');
  } catch (e) { st.textContent = e.message; st.style.color = 'var(--red)'; log('Animate failed: ' + e.message); }
  finally { $('cancelBtn').classList.add('hidden'); }
};

/* ---------- voice / lip sync ---------- */
$('voiceBtn').onclick = () => openSheet('sheet-voice');
$('voiceFile').onchange = async e => {
  if (!e.target.files[0]) return;
  const file = e.target.files[0];
  const url = URL.createObjectURL(file);
  const isVideo = file.type.startsWith('video/');
  const audio = $('voiceAudio');
  audio.src = url; audio.classList.remove('hidden');
  S.voice = { url, name: file.name };
  $('voiceInfo').textContent = '🎙️ ' + file.name;
  log('Voice picked: ' + file.name);
  // decode energy envelope (200ms windows, 10Hz)
  try {
    const buf = await file.arrayBuffer();
    const AC = window.AudioContext || window.webkitAudioContext;
    const ac = new AC();
    const decoded = await ac.decodeAudioData(buf);
    const ch = decoded.getChannelData(0);
    const sr = decoded.sampleRate, win = Math.floor(sr * 0.2);
    const env = [];
    for (let i = 0; i < ch.length; i += win) {
      let sum = 0, cnt = 0;
      for (let j = i; j < Math.min(i + win, ch.length); j++) { sum += ch[j] * ch[j]; cnt++; }
      env.push(clamp(Math.sqrt(sum / Math.max(1, cnt)) * 4, 0, 1));
    }
    // smooth
    S.voiceEnv = env.map((v, i) => (env[Math.max(0, i - 1)] + v + env[Math.min(env.length - 1, i + 1)]) / 3);
    ac.close();
    log(`Voice envelope: ${S.voiceEnv.length} windows`);
  } catch (err) { log('Voice decode failed: ' + err.message); S.voiceEnv = null; }
};

/* ---------- camera sheet (capture source/target) ---------- */
let camStream = null, camTarget = null;
async function openCameraSheet(forTarget) {
  camTarget = forTarget;
  openSheet('sheet-camera');
  try {
    camStream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: 'user' }, audio: false });
    $('camVideo').srcObject = camStream;
  } catch (e) { toast(e.message); closeSheets(); }
}
function stopCameraSheet() {
  if (camStream) { camStream.getTracks().forEach(tr => tr.stop()); camStream = null; }
}
$('camSrcBtn').onclick = () => openCameraSheet(false);
$('camTgtBtn').onclick = () => openCameraSheet(true);
$('camCancelBtn').onclick = closeSheets;
$('camCaptureBtn').onclick = () => {
  const v = $('camVideo');
  const c = document.createElement('canvas');
  c.width = v.videoWidth || 640; c.height = v.videoHeight || 480;
  c.getContext('2d').drawImage(v, 0, 0);
  c.toBlob(async b => {
    const file = new File([b], 'camera.jpg', { type: 'image/jpeg' });
    if (camTarget) await setTarget(file); else await addSource(file);
    closeSheets();
  }, 'image/jpeg', 0.92);
};

/* ---------- live ---------- */
const liveCv = $('liveCanvas'), liveCtx = liveCv.getContext('2d');
const liveVideo = document.createElement('video');
liveVideo.playsInline = true; liveVideo.muted = true;
$('liveStartBtn').onclick = async () => {
  if (S.live.on) { stopLive(); return; }
  if (!S.modelsReady) { toast(t('needModels')); return; }
  if (S.activeSrc < 0) { toast(t('needSource')); return; }
  try {
    S.live.stream = await navigator.mediaDevices.getUserMedia({
      video: { facingMode: S.live.facing, width: { ideal: 640 }, height: { ideal: 480 } }, audio: false,
    });
    liveVideo.srcObject = S.live.stream;
    await liveVideo.play();
    S.live.on = true;
    $('liveStartBtn').textContent = '⏹ Stop';
    $('liveBadge').textContent = 'on'; $('liveBadge').className = 'badge green';
    $('recBtn').disabled = false;
    toast(t('liveOn')); log('Live started (' + S.live.facing + ')');
    liveLoop();
  } catch (e) { toast(e.message); log('Live failed: ' + e.message); }
};
function stopLive() {
  S.live.on = false; cancelAnimationFrame(S.live.raf);
  if (S.live.stream) { S.live.stream.getTracks().forEach(tr => tr.stop()); S.live.stream = null; }
  if (S.live.rec && S.live.rec.state !== 'inactive') S.live.rec.stop();
  $('liveStartBtn').textContent = t('startLive');
  $('liveBadge').textContent = 'off'; $('liveBadge').className = 'badge';
  $('recBtn').disabled = true;
  toast(t('liveOff'));
}
$('flipBtn').onclick = () => {
  S.live.facing = S.live.facing === 'user' ? 'environment' : 'user';
  S.live.mirror = S.live.facing === 'user';
  if (S.live.on) { stopLive(); $('liveStartBtn').click(); }
};
let liveLastDetect = 0, liveFaces = [];
async function liveLoop() {
  if (!S.live.on) return;
  const low = localStorage.getItem('ffm_lowmem') === '1';
  const cap = low ? 320 : 480;
  const scale = Math.min(1, cap / Math.max(liveVideo.videoWidth || 640, liveVideo.videoHeight || 480));
  const W = Math.round((liveVideo.videoWidth || 640) * scale / 2) * 2;
  const H = Math.round((liveVideo.videoHeight || 480) * scale / 2) * 2;
  liveCv.width = W; liveCv.height = H;
  liveCtx.save();
  if (S.live.mirror) { liveCtx.translate(W, 0); liveCtx.scale(-1, 1); }
  liveCtx.drawImage(liveVideo, 0, 0, W, H);
  liveCtx.restore();
  const now = performance.now();
  if (now - liveLastDetect > 350) {
    liveLastDetect = now;
    try {
      const det = await faceapi.detectAllFaces(liveCv, new faceapi.TinyFaceDetectorOptions({ inputSize: 320, scoreThreshold: 0.4 }))
        .withFaceLandmarks();
      liveFaces = det;
      drawLiveBoxes(W, H);
    } catch { /* keep last */ }
  }
  if (liveFaces.length && S.activeSrc >= 0) {
    const src = S.sources[S.activeSrc].img;
    // cache source detection (refresh every 2s) — SSD per frame is too slow
    if (!S._liveSrcF || now - (S._liveSrcAt || 0) > 2000) {
      S._liveSrcF = await detectFaces(src); S._liveSrcAt = now;
    }
    const srcF = S._liveSrcF;
    if (srcF.length) {
      for (let f = 0; f < Math.min(liveFaces.length, 3); f++) {
        const asg = S.assignments[f];
        if (asg === 'keep') continue;
        swapOneFace(liveCtx, W, H, src, srcF[0].landmarks.positions, liveFaces[f].landmarks.positions,
          +$('blendSlider').value, +$('colorSlider').value, null, 0, false);
      }
    }
  }
  S.live.raf = requestAnimationFrame(liveLoop);
}
function drawLiveBoxes(W, H) {
  const box = $('liveBoxes');
  box.innerHTML = '';
  const dispW = liveCv.clientWidth || W;
  const sx = dispW / W, sy = dispW / W;
  liveFaces.forEach((f, i) => {
    const b = f.detection.box;
    const d = document.createElement('div');
    d.className = 'facebox';
    d.style.cssText = `left:${b.x * sx}px;top:${b.y * sy}px;width:${b.width * sx}px;height:${b.height * sy}px;pointer-events:auto;cursor:pointer`;
    d.innerHTML = `<span class="tag">${t('person')} ${i + 1}</span>`;
    d.onclick = () => {
      if (!$('liveAssignToggle').checked) return;
      const cur = S.assignments[i];
      if (cur === undefined) S.assignments[i] = S.activeSrc;
      else if (cur === S.activeSrc) S.assignments[i] = 'keep';
      else delete S.assignments[i];
      toast(`${t('person')} ${i + 1}: ${S.assignments[i] === 'keep' ? t('keepFace') : 'S' + ((S.assignments[i] ?? S.activeSrc) + 1)}`);
    };
    box.appendChild(d);
  });
}
$('fsBtn').onclick = () => $('liveWrap').classList.toggle('fs');
$('recBtn').onclick = async () => {
  if (S.live.rec && S.live.rec.state !== 'inactive') { S.live.rec.stop(); return; }
  const stream = liveCv.captureStream(30);
  if ($('micToggle').checked) {
    try {
      const mic = await navigator.mediaDevices.getUserMedia({ audio: true });
      mic.getAudioTracks().forEach(tr => stream.addTrack(tr));
    } catch (e) { toast(e.message); }
  }
  S.live.chunks = [];
  S.live.rec = new MediaRecorder(stream, { mimeType: pickMime() });
  S.live.rec.ondataavailable = e => e.data.size && S.live.chunks.push(e.data);
  S.live.rec.onstop = () => {
    const blob = new Blob(S.live.chunks, { type: S.live.rec.mimeType || 'video/webm' });
    $('recPreview').src = URL.createObjectURL(blob);
    $('recPreview').classList.add('show');
    $('recSaveBtn').classList.remove('hidden');
    $('recDot').innerHTML = '';
    $('recBtn').querySelector('span:last-child').textContent = t('record');
    toast(t('recSaved')); log('Live recording ready');
  };
  S.live.rec.start();
  $('recDot').innerHTML = '<span class="rec-dot"></span>';
  $('recBtn').querySelector('span:last-child').textContent = t('recording');
  log('Live recording started');
};
$('recSaveBtn').onclick = () => {
  const a = document.createElement('a');
  a.download = 'facefusion_live_' + Date.now() + '.webm';
  a.href = $('recPreview').src; a.click();
};

/* ---------- batch ---------- */
function renderBatchList() {
  const list = $('batchList');
  list.innerHTML = '';
  S.batch.forEach((b, i) => {
    const d = document.createElement('div');
    d.className = 'batch-item';
    d.innerHTML = `<img src="${b.thumb}" alt=""><div class="meta"><div class="nm">${b.name}</div><div class="st">${b.status}</div></div><button class="icon-btn" data-rm="${i}">✕</button>`;
    d.querySelector('[data-rm]').onclick = () => { S.batch.splice(i, 1); renderBatchList(); };
    list.appendChild(d);
  });
  $('batchRunBtn').disabled = !(S.batch.length && S.activeSrc >= 0 && S.modelsReady);
}
$('batchFile').onchange = async e => {
  for (const f of e.target.files) {
    const url = URL.createObjectURL(f);
    const v = document.createElement('video');
    v.src = url; v.muted = true;
    await new Promise(r => { v.onloadedmetadata = r; });
    const c = document.createElement('canvas'); c.width = 112; c.height = 112;
    c.getContext('2d').drawImage(v, 0, 0, 112, 112);
    S.batch.push({ file: f, url, name: f.name, thumb: c.toDataURL('image/jpeg', 0.7), status: t('queued') });
    URL.revokeObjectURL(url);
  }
  renderBatchList();
};
$('batchRunBtn').onclick = async () => {
  if (S.activeSrc < 0) { toast(t('needSource')); return; }
  S.cancelled = false;
  for (const item of S.batch) {
    if (S.cancelled) break;
    item.status = t('running'); renderBatchList();
    try {
      const v = document.createElement('video');
      v.src = item.url; v.muted = true; v.playsInline = true;
      await new Promise(r => { v.onloadedmetadata = r; });
      const { frames, fps, W, H } = await processVideoFrames(v, null, null);
      const blob = await encodeFrames(frames, fps, W, H);
      item.blob = blob; item.status = t('finished');
      log('Batch item done: ' + item.name);
    } catch (e) { item.status = t('failed') + ': ' + e.message; log('Batch item failed: ' + item.name); }
    renderBatchList();
  }
  $('batchStatus').textContent = t('done');
};

/* ---------- bug report / support ---------- */
$('bugBtn').onclick = async () => {
  const report = [
    'FaceFusion Mobile (web) bug report',
    'Date: ' + new Date().toISOString(),
    'UA: ' + navigator.userAgent,
    'Platform: ' + navigator.platform + ' · Cores: ' + navigator.hardwareConcurrency + ' · RAM: ' + (navigator.deviceMemory || '?') + ' GB',
    'GPU: ' + webglRenderer(),
    'Screen: ' + screen.width + '×' + screen.height + ' DPR ' + devicePixelRatio,
    'Models ready: ' + S.modelsReady + ' · Source: ' + (localStorage.getItem('ffm_dl') || 'hf'),
    'Processors: ' + JSON.stringify(S.proc) + ' · Swapper: ' + S.swapperModel,
    '--- run log ---',
    ...LOG.slice(-40),
  ].join('\n');
  try {
    if (navigator.share) { await navigator.share({ title: 'FaceFusion Mobile bug report', text: report }); }
    else { await navigator.clipboard.writeText(report); toast(t('copied')); }
  } catch { try { await navigator.clipboard.writeText(report); toast(t('copied')); } catch { toast(report.slice(0, 120)); } }
};
$('supportBtn').onclick = () => window.open('https://buymeacoffee.com/abrahampaulj', '_blank');

/* ---------- boot ---------- */
applyTheme();
applyLang();
renderSrcRow();
renderModelList();
renderDeviceInfo();
log('FaceFusion Mobile booted');
if ('serviceWorker' in navigator) navigator.serviceWorker.register('sw.js').catch(() => {});
window.addEventListener('load', () => {
  if (typeof faceapi !== 'undefined') loadModels();
  else {
    const c = setInterval(() => { if (typeof faceapi !== 'undefined') { clearInterval(c); loadModels(); } }, 200);
    setTimeout(() => clearInterval(c), 15000);
  }
});
