import { Area, AreaChart, Line, LineChart, Bar, BarChart, ResponsiveContainer } from 'recharts';
import { useViewContext } from '../ViewContext';
import { getByPath } from '../hooks/useDeferredMutate';
import type { BaseComponentProps } from '../ComponentRenderer';
import type { DataChartSpec } from '../types';

/**
 * `sparkline` — a `dataChart` stripped of axes, grid, legend and tooltip so it can sit inline
 * beside a number (a `statTile`, a table cell) without competing with it.
 *
 * Only the first series is drawn. A sparkline showing two series needs a legend to be readable,
 * and once it has a legend it is a `dataChart`.
 */
export function Sparkline({ component: c }: BaseComponentProps<DataChartSpec>) {
  const { state } = useViewContext();
  const bindKey = c.bind?.replace(/^\$\./, '');
  const raw = bindKey ? getByPath(state, bindKey) : undefined;
  const data: Record<string, unknown>[] = Array.isArray(raw) ? (raw as Record<string, unknown>[]) : [];

  const series = c.chartSeries?.[0];
  const field = series?.field ?? 'value';
  const color = series?.color ?? '#2563eb';
  const chartType = c.chartType ?? 'line';

  if (data.length === 0) return null;

  const content =
    chartType === 'bar' ? (
      <BarChart data={data}>
        <Bar dataKey={field} fill={color} isAnimationActive={false} />
      </BarChart>
    ) : chartType === 'area' ? (
      <AreaChart data={data}>
        <Area type="monotone" dataKey={field} stroke={color} fill={color} fillOpacity={0.2} dot={false} isAnimationActive={false} />
      </AreaChart>
    ) : (
      <LineChart data={data}>
        <Line type="monotone" dataKey={field} stroke={color} strokeWidth={2} dot={false} isAnimationActive={false} />
      </LineChart>
    );

  return (
    <div data-testid={c.id} style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
      {c.label && <span style={{ fontSize: 11, color: '#6b7280' }}>{c.label}</span>}
      <ResponsiveContainer width="100%" height={40}>
        {content}
      </ResponsiveContainer>
    </div>
  );
}
