import { useState, useRef } from 'react';
import { useViewContext } from '../ViewContext';
import { getByPath } from '../hooks/useDeferredMutate';
import type { BaseComponentProps } from '../ComponentRenderer';
import type { FileUploadSpec } from '../types';
import { accent, accentSoft, controlEdge, helperText, ink, inset, labelText, muted, panel, radius, requiredMark, rule, tone } from '../themeColors';

interface StoredBlobRef {
  $blobId: string;
  $mediaType: string;
  $bytes: number;
  $fileName?: string;
}

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

function isStoredBlobRef(v: unknown): v is StoredBlobRef {
  return v !== null && typeof v === 'object' && '$blobId' in (v as object);
}

function blobDownloadUrl(blobId: string): string {
  return `/blobs/${encodeURIComponent(blobId)}`;
}

/** Normalises the POST /blobs API response to $-prefixed keys regardless of server version. */
function normaliseApiRef(raw: Record<string, unknown>, fileName: string): StoredBlobRef {
  return {
    $blobId:    (raw['$blobId']    ?? raw['blobId'])    as string,
    $mediaType: (raw['$mediaType'] ?? raw['mediaType']) as string,
    $bytes:     (raw['$bytes']     ?? raw['bytes'])     as number,
    $fileName:  fileName,
  };
}

/** Returns true when the MIME type matches a comma-separated accept-style pattern. */
function matchesMediaType(mime: string, pattern: string): boolean {
  return pattern.split(',').map(s => s.trim()).some(p => {
    if (p === mime) return true;
    if (p.endsWith('/*')) return mime.startsWith(p.slice(0, -1));
    return false;
  });
}

function FileRow({
  blobRef,
  onRemove,
  readOnly,
}: {
  blobRef: StoredBlobRef;
  onRemove: () => void;
  readOnly: boolean;
}) {
  // Show a thumbnail for images; if the blob was GC'd server-side the <img> errors and we fall back
  // to the plain filename link below.
  const [thumbFailed, setThumbFailed] = useState(false);
  const isImage = typeof blobRef.$mediaType === 'string' && blobRef.$mediaType.startsWith('image/');
  const showThumb = isImage && !thumbFailed;

  return (
    <div style={{
      display: 'flex',
      alignItems: 'center',
      gap: 10,
      padding: '8px 10px',
      border: `1px solid ${rule()}`,
      borderRadius: radius.sm,
      background: inset(),
      color: ink(),
      fontSize: 13,
    }}>
      {showThumb && (
        <a
          href={blobDownloadUrl(blobRef.$blobId)}
          target="_blank"
          rel="noopener noreferrer"
          style={{ flexShrink: 0, display: 'flex' }}
        >
          <img
            src={blobDownloadUrl(blobRef.$blobId)}
            alt={blobRef.$fileName ?? 'uploaded image'}
            loading="lazy"
            onError={() => setThumbFailed(true)}
            style={{ maxHeight: 72, maxWidth: 96, borderRadius: 4, objectFit: 'cover', display: 'block' }}
          />
        </a>
      )}
      <span style={{ flex: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
        <a
          href={blobDownloadUrl(blobRef.$blobId)}
          download={blobRef.$fileName}
          style={{ fontWeight: 500, color: accent(), textDecoration: 'none' }}
          onMouseOver={e => (e.currentTarget.style.textDecoration = 'underline')}
          onMouseOut={e => (e.currentTarget.style.textDecoration = 'none')}
        >
          {blobRef.$fileName ?? blobRef.$blobId}
        </a>
        <span style={{ color: muted(), marginLeft: 8 }}>
          {blobRef.$mediaType} · {formatBytes(blobRef.$bytes)}
        </span>
      </span>
      {!readOnly && (
        <button
          type="button"
          onClick={onRemove}
          aria-label={`Remove ${blobRef.$fileName ?? 'file'}`}
          style={{
            width: 28, height: 28, flexShrink: 0, cursor: 'pointer', fontSize: 13,
            border: `1px solid ${controlEdge()}`, borderRadius: radius.sm, background: panel(), color: ink(),
          }}
        >
          ✕
        </button>
      )}
    </div>
  );
}

export function FileUploadField({ component: c, enabled, readOnly, required }: BaseComponentProps<FileUploadSpec>) {
  const { state, onMutate } = useViewContext();
  const inputRef = useRef<HTMLInputElement>(null);
  const [uploading, setUploading] = useState(false);
  const [errors, setErrors] = useState<string[]>([]);

  const multiple = c.multiple === true;
  const minFiles = c.minFiles ?? 0;
  const maxFiles = c.maxFiles ?? (multiple ? Infinity : 1);
  const minSize  = c.minSize  ?? 0;
  const maxSize  = c.maxSize  ?? Infinity;
  const allowedTypes = c.allowedMediaTypes ?? c.accept;

  const bindKey  = c.bind?.replace(/^\$\./, '');
  const rawValue = bindKey ? getByPath(state, bindKey) : undefined;

  // Support both single (object | null) and multi (array) modes
  const files: StoredBlobRef[] = multiple
    ? (Array.isArray(rawValue) ? rawValue.filter(isStoredBlobRef) : [])
    : (isStoredBlobRef(rawValue) ? [rawValue] : []);

  function validateFile(file: File): string | null {
    if (allowedTypes && !matchesMediaType(file.type, allowedTypes)) {
      return `"${file.name}" is not an allowed file type (${allowedTypes})`;
    }
    if (file.size < minSize) {
      return `"${file.name}" is below the minimum size of ${formatBytes(minSize)}`;
    }
    if (file.size > maxSize) {
      return `"${file.name}" exceeds the maximum size of ${formatBytes(maxSize)}`;
    }
    return null;
  }

  async function uploadFile(file: File): Promise<StoredBlobRef> {
    const form = new FormData();
    form.append('file', file);
    const res = await fetch('/blobs', { method: 'POST', body: form });
    if (!res.ok) throw new Error(`Upload failed (${res.status})`);
    const raw = await res.json() as Record<string, unknown>;
    return normaliseApiRef(raw, file.name);
  }

  async function handleFileChange(e: React.ChangeEvent<HTMLInputElement>) {
    const selected = Array.from(e.target.files ?? []);
    if (selected.length === 0 || !c.bind) return;

    const errs: string[] = [];

    // Validate each selected file
    const valid = selected.filter(f => {
      const err = validateFile(f);
      if (err) errs.push(err);
      return !err;
    });

    // Count check (before upload)
    const projectedCount = files.length + valid.length;
    if (maxFiles !== Infinity && projectedCount > maxFiles) {
      errs.push(`Maximum ${maxFiles} file${maxFiles === 1 ? '' : 's'} allowed`);
      setErrors(errs);
      if (inputRef.current) inputRef.current.value = '';
      return;
    }

    if (errs.length > 0) {
      setErrors(errs);
      if (inputRef.current) inputRef.current.value = '';
      return;
    }

    setErrors([]);
    setUploading(true);
    try {
      const uploaded = await Promise.all(valid.map(uploadFile));
      const next = multiple ? [...files, ...uploaded] : uploaded[0];
      await onMutate({ [c.bind]: next ?? null });
    } catch (err) {
      setErrors([String(err)]);
    } finally {
      setUploading(false);
      if (inputRef.current) inputRef.current.value = '';
    }
  }

  async function handleRemove(index: number) {
    if (!c.bind || readOnly) return;
    if (multiple) {
      const next = files.filter((_, i) => i !== index);
      await onMutate({ [c.bind]: next.length > 0 ? next : null });
    } else {
      await onMutate({ [c.bind]: null });
    }
    setErrors([]);
  }

  const canAddMore = !readOnly && enabled && files.length < maxFiles;
  const countHint  = minFiles > 0 || maxFiles !== Infinity
    ? (minFiles > 0 && maxFiles !== Infinity
        ? `${minFiles}–${maxFiles} files`
        : minFiles > 0
          ? `At least ${minFiles} file${minFiles === 1 ? '' : 's'}`
          : `Up to ${maxFiles} file${maxFiles === 1 ? '' : 's'}`)
    : null;

  // Validation: minFiles not met
  const countError = !readOnly && minFiles > 0 && files.length < minFiles
    ? `At least ${minFiles} file${minFiles === 1 ? '' : 's'} required`
    : null;

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
      {c.label && (
        <label htmlFor={`${c.id}-file`} style={labelText}>
          {c.label}
          {required && <span style={requiredMark} aria-hidden="true">*</span>}
        </label>
      )}

      {/* Uploaded files list */}
      {files.length > 0 && (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
          {files.map((f, i) => (
            <FileRow
              key={f.$blobId}
              blobRef={f}
              readOnly={readOnly}
              onRemove={() => handleRemove(i)}
            />
          ))}
        </div>
      )}

      {/* File picker — shown when more files can be added */}
      {canAddMore && (
        // The native picker reads "Choose File / No file chosen" in a grey system button that
        // ignores the theme. The input stays in the DOM (keyboard, screen readers, drag-and-drop
        // onto it all keep working) and is drawn as a dashed drop area instead.
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <label
            style={{
              position: 'relative', display: 'flex', alignItems: 'center', gap: 10, flex: 1,
              minHeight: 44, padding: '8px 12px', border: `1px dashed ${controlEdge()}`,
              borderRadius: radius.sm, background: panel(), color: ink(), fontSize: 14,
              cursor: uploading ? 'progress' : 'pointer',
            }}
          >
            <span aria-hidden="true" style={{
              display: 'inline-flex', alignItems: 'center', minHeight: 30, padding: '0 12px',
              borderRadius: radius.sm, background: accentSoft(), color: ink(), fontWeight: 600, fontSize: 13,
            }}>
              {multiple ? 'Choose files' : 'Choose a file'}
            </span>
            <span style={{ color: muted(), fontSize: 13 }}>{uploading ? 'Uploading…' : 'or drop it here'}</span>
            <input
              ref={inputRef}
              id={`${c.id}-file`}
              type="file"
              accept={c.accept}
              multiple={multiple}
              disabled={uploading}
              style={{ position: 'absolute', inset: 0, width: '100%', height: '100%', opacity: 0, cursor: 'inherit' }}
              onChange={handleFileChange}
            />
          </label>
        </div>
      )}

      {/* Hints and errors */}
      {countHint && (
        <span style={helperText}>
          {countHint}{maxSize !== Infinity ? `, max ${formatBytes(maxSize)} each` : ''}
          {allowedTypes ? ` · ${allowedTypes}` : ''}
        </span>
      )}
      {countError && (
        <span role="alert" style={{ ...helperText, color: tone.dangerText() }}>{countError}</span>
      )}
      {errors.map((e, i) => (
        <span key={i} role="alert" style={{ ...helperText, color: tone.dangerText() }}>{e}</span>
      ))}
      {c.helperText && (
        <span style={helperText}>{c.helperText}</span>
      )}
    </div>
  );
}
