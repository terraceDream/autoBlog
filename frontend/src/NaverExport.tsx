import { useEffect, useMemo, useState } from 'react';
import { api } from './api';
import './naver-export.css';

type ExportImage = {
  index: number;
  url: string;
  sourceUrl: string;
  credit: string;
  license: string;
  licenseUrl: string;
  caption: string;
  afterParagraph: number;
  downloadUrl: string;
};
type ExportTable = { index: number; html: string; tsv: string };
type ExportData = {
  draftId: string;
  title: string;
  category: string;
  tags: string[];
  html: string;
  htmlWithImages: string;
  plainText: string;
  images: ExportImage[];
  tables: ExportTable[];
  warnings: string[];
};
type Fallback = { label: string; text: string; html?: string };

// Typography is applied to individual elements because pasted editors may discard stylesheet rules.
export function styleNaverHtml(
  html: string,
  bodySize: number,
  headingSize: number,
  font: string,
  lineHeight = 1.8,
) {
  const parsed = new DOMParser().parseFromString(html, 'text/html');
  const family = font === 'sans-serif' ? 'sans-serif' : "'맑은 고딕', 'Malgun Gothic', sans-serif";
  const blocks = parsed.body.querySelectorAll<HTMLElement>(
    'article,section,div,p,li,td,th,blockquote,ul,ol,h1,h2,h3,h4,h5,h6',
  );
  for (const block of blocks) {
    block.style.fontFamily = family;
    block.style.fontSize = `${/^H[1-6]$/.test(block.tagName) ? headingSize : bodySize}px`;
    block.style.lineHeight = String(lineHeight);
  }
  // Child spans sometimes carry the draft's original font size and would override their paragraph.
  for (const span of parsed.body.querySelectorAll<HTMLElement>('span,strong,b,em,i,a')) {
    span.style.fontFamily = family;
    span.style.fontSize = `${span.closest('h1,h2,h3,h4,h5,h6') ? headingSize : bodySize}px`;
    span.style.lineHeight = String(lineHeight);
  }
  const wrapper = parsed.createElement('div');
  wrapper.style.fontFamily = family;
  wrapper.style.fontSize = `${bodySize}px`;
  wrapper.style.lineHeight = String(lineHeight);
  wrapper.style.color = '#222222';
  while (parsed.body.firstChild) wrapper.append(parsed.body.firstChild);
  return wrapper.outerHTML;
}

function ImagePreview({ image }: { image: ExportImage }) {
  const [failed, setFailed] = useState(false);
  return failed ? (
    <p className="naver-export-note">
      이미지 미리보기를 불러오지 못했습니다. 아래 출처 링크에서 확인할 수 있습니다.
    </p>
  ) : (
    <img
      className="naver-export-thumbnail"
      src={image.url}
      alt={image.caption || `업로드할 이미지 ${image.index}`}
      loading="lazy"
      referrerPolicy="no-referrer"
      onError={() => setFailed(true)}
    />
  );
}

function previewDocument(html: string) {
  return `<!doctype html><html lang="ko"><head><meta charset="utf-8"><meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src https: http: data:; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'"><style>body{margin:0;padding:24px;background:white;overflow-wrap:anywhere}img{max-width:100%;height:auto}table{max-width:100%;border-collapse:collapse}td,th{overflow-wrap:anywhere}p{margin:0 0 1em}a{color:#17755d}</style></head><body>${html}</body></html>`;
}

async function downloadFile(url: string, fallbackName: string, draftId: string) {
  const safeUrl = new URL(url, window.location.origin);
  if (
    safeUrl.origin !== window.location.origin ||
    !safeUrl.pathname.startsWith(`/api/drafts/${encodeURIComponent(draftId)}/`)
  ) {
    throw new Error('다운로드 주소를 확인하지 못했습니다. 자료를 다시 불러와 주세요.');
  }
  const response = await fetch(safeUrl);
  if (!response.ok) {
    const error = await response.json().catch(() => ({}));
    throw new Error(error.message || error.detail || '다운로드하지 못했습니다. 다시 시도해 주세요.');
  }
  const blob = await response.blob();
  if (!blob.size) throw new Error('다운로드한 파일이 비어 있습니다. 다시 시도해 주세요.');
  const disposition = response.headers.get('content-disposition') || '';
  const encoded = disposition.match(/filename\*=UTF-8''([^;]+)/i)?.[1];
  const simple = disposition.match(/filename="([^"]+)"/i)?.[1];
  let filename = simple || fallbackName;
  if (encoded) {
    try {
      filename = decodeURIComponent(encoded);
    } catch {
      filename = fallbackName;
    }
  }
  const objectUrl = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = objectUrl;
  anchor.download = filename.replace(/[\\/\u0000-\u001f]/g, '_');
  document.body.append(anchor);
  anchor.click();
  anchor.remove();
  window.setTimeout(() => URL.revokeObjectURL(objectUrl), 1000);
}

export function NaverExport({ draftId, revisionKey }: { draftId: string; revisionKey?: string }) {
  const [open, setOpen] = useState(false);
  const [data, setData] = useState<ExportData | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [refresh, setRefresh] = useState(0);
  const [loadedKey, setLoadedKey] = useState('');
  const [bodySize, setBodySize] = useState(18);
  const [headingSize, setHeadingSize] = useState(24);
  const [lineHeight, setLineHeight] = useState(1.8);
  const [font, setFont] = useState('malgun');
  const [includeImages, setIncludeImages] = useState(false);
  const [notice, setNotice] = useState('');
  const [fallback, setFallback] = useState<Fallback | null>(null);
  const [downloading, setDownloading] = useState('');
  const key = `${draftId}:${revisionKey || ''}:${refresh}`;
  const current = data && loadedKey === key ? data : null;

  useEffect(() => {
    if (!open) return;
    let alive = true;
    setLoading(true);
    setError('');
    setNotice('');
    setFallback(null);
    api<ExportData>(`/drafts/${encodeURIComponent(draftId)}/naver-export`)
      .then((value) => {
        if (alive) {
          setData(value);
          setLoadedKey(key);
        }
      })
      .catch((failure) => alive && setError((failure as Error).message))
      .finally(() => alive && setLoading(false));
    return () => {
      alive = false;
    };
  }, [open, draftId, key]);

  const styledHtml = useMemo(
    () =>
      current
        ? styleNaverHtml(
            includeImages ? current.htmlWithImages : current.html,
            bodySize,
            headingSize,
            font,
            lineHeight,
          )
        : '',
    [current, includeImages, bodySize, headingSize, font, lineHeight],
  );
  const preview = useMemo(() => previewDocument(styledHtml), [styledHtml]);

  async function copy(label: string, text: string, html?: string) {
    setNotice('');
    setError('');
    setFallback(null);
    if (html && navigator.clipboard?.write && typeof ClipboardItem !== 'undefined') {
      try {
        await navigator.clipboard.write([
          new ClipboardItem({
            'text/html': new Blob([html], { type: 'text/html' }),
            'text/plain': new Blob([text], { type: 'text/plain' }),
          }),
        ]);
        setNotice(
          `${label}의 서식과 텍스트를 복사했습니다. 네이버에 붙여넣은 뒤 표와 글자 크기를 확인해 주세요.`,
        );
        return;
      } catch {
        // Text copying can still be permitted when the browser rejects rich clipboard formats.
      }
    }
    try {
      await navigator.clipboard.writeText(text);
      setNotice(
        html
          ? `${label} 텍스트 복사 완료. 이 브라우저에서는 서식 복사가 허용되지 않아 표·서식을 따로 맞춰야 합니다.`
          : `${label} 복사 완료.`,
      );
      if (html) setFallback({ label, text, html });
    } catch {
      setNotice('브라우저에서 복사를 허용하지 않았습니다. 아래 내용을 선택해 직접 복사해 주세요.');
      setFallback({ label, text, html });
    }
  }

  async function download(url: string, name: string, id: string) {
    setDownloading(id);
    setError('');
    setNotice('');
    try {
      await downloadFile(url, name, draftId);
      setNotice('다운로드를 시작했습니다. 브라우저의 다운로드 목록에서 파일을 확인해 주세요.');
    } catch (failure) {
      setError((failure as Error).message || '다운로드 중 연결이 끊겼습니다. 다시 시도해 주세요.');
    } finally {
      setDownloading('');
    }
  }

  return (
    <section className="naver-export" aria-label="네이버 블로그용 내보내기">
      <button
        type="button"
        className="naver-export-toggle"
        aria-expanded={open}
        onClick={() => setOpen((value) => !value)}
      >
        <span className="naver-export-mark" aria-hidden="true">
          N
        </span>
        <span>
          <strong>네이버 블로그용 복사·다운로드</strong>
          <small>저장된 초안을 그대로 활용해 직접 게시</small>
        </span>
        <span className="naver-export-chevron" aria-hidden="true">
          {open ? '−' : '+'}
        </span>
      </button>
      {open && (
        <div className="naver-export-content">
          <p className="naver-export-saved">
            서버에 저장된 버전 기준입니다. 초안을 수정했다면 먼저 저장해 주세요.
          </p>
          <ol className="naver-export-steps">
            <li>
              <b>1</b>제목·본문 복사
            </li>
            <li>
              <b>2</b>네이버에 붙여넣기
            </li>
            <li>
              <b>3</b>이미지 업로드
            </li>
            <li>
              <b>4</b>카테고리 선택·비공개 저장
            </li>
          </ol>
          <p className="naver-export-note">
            네이버 로그인·저장은 직접 진행합니다. 붙여넣기 후 이미지와 표, 글꼴·크기는 네이버 편집기에서
            확인해 주세요.
          </p>
          {loading && (
            <p role="status" className="naver-export-note">
              저장된 초안으로 복사 자료를 준비하고 있습니다…
            </p>
          )}
          {error && (
            <p className="naver-export-error" role="alert">
              {error}
            </p>
          )}
          {!loading && !current && (
            <button type="button" className="button" onClick={() => setRefresh((value) => value + 1)}>
              다시 불러오기
            </button>
          )}
          {current && (
            <>
              <div className="naver-export-metadata">
                <div>
                  <span className="naver-export-label">제목</span>
                  <strong>{current.title}</strong>
                </div>
                <button type="button" className="button" onClick={() => void copy('제목', current.title)}>
                  제목 복사
                </button>
                <div>
                  <span className="naver-export-label">추천 카테고리</span>
                  <span>{current.category || '분류 없음'}</span>
                </div>
                <span className="naver-export-note">네이버에서 직접 선택</span>
                <div>
                  <span className="naver-export-label">태그</span>
                  <span>{current.tags.map((tag) => `#${tag.replace(/^#/, '')}`).join(' ') || '없음'}</span>
                </div>
                <button
                  type="button"
                  className="button"
                  disabled={!current.tags.length}
                  onClick={() =>
                    void copy('태그', current.tags.map((tag) => `#${tag.replace(/^#/, '')}`).join(' '))
                  }
                >
                  태그 복사
                </button>
              </div>
              <div className="naver-export-format">
                <label>
                  글꼴
                  <select value={font} onChange={(event) => setFont(event.target.value)}>
                    <option value="malgun">맑은 고딕</option>
                    <option value="sans-serif">기본 고딕</option>
                  </select>
                </label>
                <label>
                  본문 크기
                  <select value={bodySize} onChange={(event) => setBodySize(Number(event.target.value))}>
                    {[16, 18, 20].map((size) => (
                      <option key={size} value={size}>
                        {size}px
                      </option>
                    ))}
                  </select>
                </label>
                <label>
                  소제목 크기
                  <select
                    value={headingSize}
                    onChange={(event) => setHeadingSize(Number(event.target.value))}
                  >
                    {[22, 24, 28].map((size) => (
                      <option key={size} value={size}>
                        {size}px
                      </option>
                    ))}
                  </select>
                </label>
                <label>
                  줄 간격
                  <select value={lineHeight} onChange={(event) => setLineHeight(Number(event.target.value))}>
                    {[1.6, 1.8, 2.0].map((value) => (
                      <option key={value} value={value}>
                        {value.toFixed(1)}
                      </option>
                    ))}
                  </select>
                </label>
              </div>
              <label className="naver-export-image-option">
                <input
                  type="checkbox"
                  checked={includeImages}
                  onChange={(event) => setIncludeImages(event.target.checked)}
                />
                <span>
                  외부 이미지도 본문과 함께 복사 시도
                  <small>
                    기본은 이미지 위치 표시입니다. 함께 복사한 이미지도 네이버에서 누락될 수 있습니다.
                  </small>
                </span>
              </label>
              <div className="naver-export-actions">
                <button
                  type="button"
                  className="button naver-export-primary"
                  onClick={() => void copy('본문', current.plainText, styledHtml)}
                >
                  본문 서식 포함 복사
                </button>
                <button
                  type="button"
                  className="button"
                  onClick={() => void copy('본문 텍스트', current.plainText)}
                >
                  텍스트만 복사
                </button>
                <button
                  type="button"
                  className="button"
                  disabled={Boolean(downloading)}
                  onClick={() =>
                    void download(
                      `/api/drafts/${encodeURIComponent(draftId)}/naver-export.zip`,
                      `naver-${draftId}.zip`,
                      'zip',
                    )
                  }
                >
                  {downloading === 'zip' ? '자료 묶음 준비 중…' : '게시 자료 ZIP 다운로드'}
                </button>
              </div>
              <p className="naver-export-note">
                ZIP은 저장된 초안의 기본 서식으로 제공합니다. 위 글꼴·크기·줄 간격 설정은 본문 복사와
                미리보기에 적용됩니다.
              </p>
              {notice && (
                <p className="naver-export-notice" role="status">
                  {notice}
                </p>
              )}
              {fallback && (
                <div className="naver-export-fallback">
                  <strong>{fallback.label} 직접 복사</strong>
                  <p>아래 내용을 클릭하면 전체 선택됩니다. Ctrl+C(또는 ⌘C)로 복사하세요.</p>
                  <textarea
                    aria-label={`${fallback.label} 직접 복사할 텍스트`}
                    readOnly
                    rows={7}
                    value={fallback.text}
                    onFocus={(event) => event.currentTarget.select()}
                  />
                  {fallback.html && (
                    <p>
                      서식이 필요하면 아래 미리보기에서 본문을 직접 선택해 복사할 수 있습니다. 표는 하단의
                      개별 복사도 이용해 주세요.
                    </p>
                  )}
                </div>
              )}
              <div className="naver-export-workspace">
                <div className="naver-export-preview">
                  <div className="naver-export-block-heading">
                    <h4>붙여넣을 본문 미리보기</h4>
                    <span>
                      {bodySize}px · 줄 간격 {lineHeight.toFixed(1)}
                    </span>
                  </div>
                  <iframe
                    title="네이버용 본문 미리보기"
                    sandbox=""
                    referrerPolicy="no-referrer"
                    srcDoc={preview}
                  />
                </div>
                <aside className="naver-export-assets" aria-label="이미지와 표 자료">
                  <div className="naver-export-block-heading">
                    <h4>이미지 {current.images.length}개</h4>
                    <span>위치에 맞춰 업로드</span>
                  </div>
                  {!current.images.length && (
                    <p className="naver-export-note">이 초안에 저장된 사용 가능 이미지가 없습니다.</p>
                  )}
                  {current.images.length > 0 && (
                    <p className="naver-export-note">
                      본문의 이미지 표시 위치에 업로드한 뒤 위치 표시 문구를 지워 주세요. 설명·출처는 남겨
                      주세요.
                    </p>
                  )}
                  {current.images.map((image) => (
                    <article className="naver-export-asset" key={image.index}>
                      <div className="naver-export-asset-title">
                        <strong>이미지 {image.index}</strong>
                        <span>
                          {image.afterParagraph > 0
                            ? `${image.afterParagraph}번째 문단 뒤`
                            : '본문의 이미지 위치 표시 참고'}
                        </span>
                      </div>
                      <ImagePreview key={image.url} image={image} />
                      <p>{image.caption || '설명 없는 이미지'}</p>
                      <p className="naver-export-credit">
                        {[image.credit, image.license].filter(Boolean).join(' · ') ||
                          '출처 정보는 원본 링크에서 확인해 주세요.'}
                      </p>
                      <div className="naver-export-asset-links">
                        {image.sourceUrl && (
                          <a href={image.sourceUrl} target="_blank" rel="noreferrer">
                            이미지 출처 ↗
                          </a>
                        )}
                        {image.licenseUrl && (
                          <a href={image.licenseUrl} target="_blank" rel="noreferrer">
                            이용 조건 ↗
                          </a>
                        )}
                      </div>
                      <div className="naver-export-actions">
                        <button
                          type="button"
                          className="button"
                          disabled={Boolean(downloading)}
                          onClick={() =>
                            void download(
                              image.downloadUrl,
                              `naver-image-${image.index}`,
                              `image-${image.index}`,
                            )
                          }
                        >
                          {downloading === `image-${image.index}` ? '다운로드 중…' : '이미지 다운로드'}
                        </button>
                        <button
                          type="button"
                          className="button"
                          onClick={() =>
                            void copy(
                              `이미지 ${image.index} 설명·출처`,
                              [image.caption, image.credit, image.sourceUrl, image.license, image.licenseUrl]
                                .filter(Boolean)
                                .join('\n'),
                            )
                          }
                        >
                          설명·출처 복사
                        </button>
                      </div>
                    </article>
                  ))}
                  <div className="naver-export-block-heading">
                    <h4>표 {current.tables.length}개</h4>
                    <span>편집 가능한 표</span>
                  </div>
                  {!current.tables.length && <p className="naver-export-note">이 초안에는 표가 없습니다.</p>}
                  {current.tables.map((table) => (
                    <article className="naver-export-asset" key={table.index}>
                      <strong>표 {table.index}</strong>
                      <p className="naver-export-note">
                        본문에서 표가 깨지면 개별 복사를 시도해 주세요. 그래도 유지되지 않으면 네이버에서 표를
                        만든 뒤 셀 내용을 옮겨 주세요.
                      </p>
                      <div className="naver-export-actions">
                        <button
                          type="button"
                          className="button"
                          onClick={() =>
                            void copy(
                              `표 ${table.index}`,
                              table.tsv,
                              styleNaverHtml(table.html, bodySize, headingSize, font, lineHeight),
                            )
                          }
                        >
                          표 서식 포함 복사
                        </button>
                        <button
                          type="button"
                          className="button"
                          onClick={() => void copy(`표 ${table.index} 셀 텍스트`, table.tsv)}
                        >
                          셀 텍스트 복사
                        </button>
                      </div>
                    </article>
                  ))}
                </aside>
              </div>
              {current.warnings.length > 0 && (
                <div className="naver-export-warnings">
                  <strong>게시 전 확인</strong>
                  <ul>
                    {current.warnings.map((warning, index) => (
                      <li key={index}>{warning}</li>
                    ))}
                  </ul>
                </div>
              )}
            </>
          )}
        </div>
      )}
    </section>
  );
}
