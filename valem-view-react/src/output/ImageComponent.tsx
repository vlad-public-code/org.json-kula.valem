import { useViewContext } from '../ViewContext';
import { getByPath } from '../hooks/useDeferredMutate';
import { useJSONataLiteral } from '../hooks/useJSONata';
import type { BaseComponentProps } from '../ComponentRenderer';
import type { ImageSpec } from '../types';

/**
 * `image` — a literal `src`, a JSONata expression, or the bound value.
 *
 * A bound value is usually what a `fileUploadField` wrote: a `BlobRef` of the shape
 * `{$blobId, $mediaType, $bytes}`. Resolving that to `/blobs/{id}` here is what makes the
 * upload and its preview two components over one path instead of a special case inside the
 * uploader.
 */
export function ImageComponent({ component: c }: BaseComponentProps<ImageSpec>) {
  const { state } = useViewContext();
  const fromSpec = useJSONataLiteral(typeof c.src === 'string' ? c.src : undefined, state);

  const bindPath = c.bind?.replace(/^\$\./, '');
  const bound = bindPath ? getByPath(state, bindPath) : undefined;
  const src = fromSpec ?? blobUrl(bound) ?? (typeof bound === 'string' ? bound : undefined);

  if (!src) return null;

  return (
    <figure data-testid={c.id} style={{ margin: 0, display: 'flex', flexDirection: 'column', gap: 4 }}>
      <img
        src={src}
        alt={c.alt ?? ''}
        style={{
          width: c.width ?? 'auto',
          height: c.height ?? 'auto',
          maxWidth: '100%',
          objectFit: (c.fit as React.CSSProperties['objectFit']) ?? 'contain',
          borderRadius: 6,
          display: 'block',
        }}
      />
      {c.label && (
        <figcaption style={{ fontSize: 11, color: '#6b7280' }}>{c.label}</figcaption>
      )}
    </figure>
  );
}

function blobUrl(value: unknown): string | undefined {
  if (value == null || typeof value !== 'object') return undefined;
  const id = (value as Record<string, unknown>).$blobId;
  return typeof id === 'string' ? `/blobs/${id}` : undefined;
}
