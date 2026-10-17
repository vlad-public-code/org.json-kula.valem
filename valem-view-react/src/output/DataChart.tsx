import {
  BarChart, Bar, LineChart, Line, AreaChart, Area,
  PieChart, Pie, Cell,
  XAxis, YAxis, CartesianGrid, Tooltip, Legend, ResponsiveContainer,
} from 'recharts';
import { useViewContext } from '../ViewContext';
import { getByPath } from '../hooks/useDeferredMutate';
import type { BaseComponentProps } from '../ComponentRenderer';
import type { DataChartSpec } from '../types';
import { ink, labelText, muted, panel, rule } from '../themeColors';

// SVG attributes cannot read CSS variables, so the series palette is fixed: the brand accent first,
// then hues that keep 3:1 or better against both the light and the dark panel.
export const CHART_COLORS = ['#5b57eb', '#0e9f6e', '#d9770a', '#e5484d', '#0891b2', '#a855f7'];
const COLORS = CHART_COLORS;
// Axes and grid draw in currentColor, which the wrapper sets to the muted text token: the one way
// to make recharts' attribute-painted ticks follow the theme.
const AXIS = { tick: { fill: 'currentColor', fontSize: 12 }, stroke: 'currentColor', strokeOpacity: 0.35 } as const;
// Recharts colours legend TEXT with the series colour, which is a fill (3.4:1 as text on the dark
// panel); the swatch keeps the colour and the words take the page ink.
const legendText = (value: string) => <span style={{ color: ink() }}>{value}</span>;
const TOOLTIP = {
  contentStyle: { background: panel(), border: `1px solid ${rule()}`, borderRadius: 8, color: ink(), fontSize: 13 },
  itemStyle: { color: ink() },
  labelStyle: { color: muted() },
  cursor: { fill: 'currentColor', fillOpacity: 0.06 },
} as const;

export function DataChart({ component: c }: BaseComponentProps<DataChartSpec>) {
  const { state } = useViewContext();
  const bindKey = c.bind?.replace(/^\$\./, '');
  const raw = bindKey ? getByPath(state, bindKey) : undefined;
  const data: Record<string, unknown>[] = Array.isArray(raw) ? (raw as Record<string, unknown>[]) : [];
  const series = c.chartSeries ?? [];
  const chartType = c.chartType ?? 'bar';

  const content =
    chartType === 'pie' ? (
      <PieChart>
        <Pie data={data} dataKey={series[0]?.field ?? 'value'} nameKey={c.chartX ?? 'name'} label>
          {data.map((_, i) => (
            <Cell key={i} fill={COLORS[i % COLORS.length]} />
          ))}
        </Pie>
        <Tooltip {...TOOLTIP} />
        <Legend wrapperStyle={{ fontSize: 13 }} formatter={legendText} />
      </PieChart>
    ) : chartType === 'line' ? (
      <LineChart data={data}>
        <CartesianGrid strokeDasharray="3 3" stroke="currentColor" strokeOpacity={0.18} vertical={false} />
        <XAxis dataKey={c.chartX ?? 'name'} {...AXIS} />
        <YAxis {...AXIS} width={52} />
        <Tooltip {...TOOLTIP} />
        <Legend wrapperStyle={{ fontSize: 13 }} formatter={legendText} />
        {series.map((s, i) => (
          <Line key={s.field} type="monotone" dataKey={s.field} name={s.label ?? s.field} stroke={s.color ?? COLORS[i % COLORS.length]} />
        ))}
      </LineChart>
    ) : chartType === 'area' ? (
      <AreaChart data={data}>
        <CartesianGrid strokeDasharray="3 3" stroke="currentColor" strokeOpacity={0.18} vertical={false} />
        <XAxis dataKey={c.chartX ?? 'name'} {...AXIS} />
        <YAxis {...AXIS} width={52} />
        <Tooltip {...TOOLTIP} />
        <Legend wrapperStyle={{ fontSize: 13 }} formatter={legendText} />
        {series.map((s, i) => (
          <Area key={s.field} type="monotone" dataKey={s.field} name={s.label ?? s.field} stroke={s.color ?? COLORS[i % COLORS.length]} fill={s.color ?? COLORS[i % COLORS.length]} fillOpacity={0.2} />
        ))}
      </AreaChart>
    ) : (
      <BarChart data={data}>
        <CartesianGrid strokeDasharray="3 3" stroke="currentColor" strokeOpacity={0.18} vertical={false} />
        <XAxis dataKey={c.chartX ?? 'name'} {...AXIS} />
        <YAxis {...AXIS} width={52} />
        <Tooltip {...TOOLTIP} />
        <Legend wrapperStyle={{ fontSize: 13 }} formatter={legendText} />
        {series.map((s, i) => (
          <Bar key={s.field} dataKey={s.field} name={s.label ?? s.field} fill={s.color ?? COLORS[i % COLORS.length]} radius={[4, 4, 0, 0]} maxBarSize={56} />
        ))}
      </BarChart>
    );

  return (
    <div style={{ color: muted() }}>
      {c.label && <div style={{ ...labelText, marginBottom: 6 }}>{c.label}</div>}
      <ResponsiveContainer width="100%" height={300}>
        {content}
      </ResponsiveContainer>
    </div>
  );
}
