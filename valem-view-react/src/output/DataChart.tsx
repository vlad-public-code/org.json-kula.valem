import {
  BarChart, Bar, LineChart, Line, AreaChart, Area,
  PieChart, Pie, Cell,
  XAxis, YAxis, CartesianGrid, Tooltip, Legend, ResponsiveContainer,
} from 'recharts';
import { useViewContext } from '../ViewContext';
import { getByPath } from '../hooks/useDeferredMutate';
import type { BaseComponentProps } from '../ComponentRenderer';
import type { DataChartSpec } from '../types';

const COLORS = ['#2563eb', '#16a34a', '#dc2626', '#d97706', '#7c3aed', '#0891b2'];

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
        <Tooltip />
        <Legend />
      </PieChart>
    ) : chartType === 'line' ? (
      <LineChart data={data}>
        <CartesianGrid strokeDasharray="3 3" />
        <XAxis dataKey={c.chartX ?? 'name'} />
        <YAxis />
        <Tooltip />
        <Legend />
        {series.map((s, i) => (
          <Line key={s.field} type="monotone" dataKey={s.field} name={s.label ?? s.field} stroke={s.color ?? COLORS[i % COLORS.length]} />
        ))}
      </LineChart>
    ) : chartType === 'area' ? (
      <AreaChart data={data}>
        <CartesianGrid strokeDasharray="3 3" />
        <XAxis dataKey={c.chartX ?? 'name'} />
        <YAxis />
        <Tooltip />
        <Legend />
        {series.map((s, i) => (
          <Area key={s.field} type="monotone" dataKey={s.field} name={s.label ?? s.field} stroke={s.color ?? COLORS[i % COLORS.length]} fill={s.color ?? COLORS[i % COLORS.length]} fillOpacity={0.2} />
        ))}
      </AreaChart>
    ) : (
      <BarChart data={data}>
        <CartesianGrid strokeDasharray="3 3" />
        <XAxis dataKey={c.chartX ?? 'name'} />
        <YAxis />
        <Tooltip />
        <Legend />
        {series.map((s, i) => (
          <Bar key={s.field} dataKey={s.field} name={s.label ?? s.field} fill={s.color ?? COLORS[i % COLORS.length]} />
        ))}
      </BarChart>
    );

  return (
    <div>
      {c.label && <div style={{ fontSize: 13, fontWeight: 600, marginBottom: 6 }}>{c.label}</div>}
      <ResponsiveContainer width="100%" height={300}>
        {content}
      </ResponsiveContainer>
    </div>
  );
}
