import {
  Bar,
  BarChart,
  CartesianGrid,
  Cell,
  Legend,
  Line,
  LineChart,
  Pie,
  PieChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts';
import { money, moneyShort } from './format';

/*
 * Thin Recharts wrappers, themed once.
 *
 * The library's defaults assume a light ground: axes, grid and legend all come out too
 * dark to see on #0B0D12. Rather than restating that on every chart, the theme lives here
 * -- the same move ChartTheme.kt makes on the phone for MPAndroidChart, for the same
 * reason.
 */

const AXIS = { stroke: '#8B92A4', fontSize: 11 };
const GRID = '#262B36';

// Series colours come from the app palette. Blue is the brand, green is money arriving,
// red is money owed -- the four-tone system the phone already trained people on.
export const SERIES = {
  primary: '#5B87FF',
  accent: '#22C55E',
  error: '#FF6B6B',
  muted: '#8B92A4',
};

// For categorical charts, where the slices carry no inherent ranking: one hue stepped in
// lightness, so no slice claims an importance the data does not give it.
//
// ⚠️ The steps stop well short of the ground. An earlier version ran down to #172177,
// which on #0B0D12 left the last three slices -- and their legend entries -- effectively
// invisible: they read as greyed out rather than as data. The darkest step here still
// clears the background.
const CATEGORICAL = [
  '#8FB0FF', '#7BA0FF', '#6B93FF', '#5B87FF', '#4E7BF5',
  '#4370E8', '#3A66DB', '#325CCE', '#2B53C1', '#254AB4',
];

const tooltipStyle = {
  contentStyle: {
    background: '#1B1F29',
    border: '1px solid #262B36',
    borderRadius: 8,
    fontSize: 12,
  },
  labelStyle: { color: '#E8EAF0' },
  // ⚠️ itemStyle is deliberately NOT set. Recharts prints each value in its own series
  // colour, and that colour is CARRYING INFORMATION: on the two-line chart it is the only
  // thing saying whether you are reading veresiye or tahsilat. Forcing it to white made
  // every tooltip legible and identical, which is a worse trade -- legibility bought by
  // deleting the label.
  //
  // The palette below is what makes the colours readable at this size instead.
};

/**
 * A titled frame around a chart.
 *
 * ⚠️ It does NOT wrap the chart in a ResponsiveContainer, and that is the fix for a bug
 * that cost a screenshot to find: ResponsiveContainer measures its box and then clones its
 * single child with explicit width/height. Passing the chart through a `children` prop and
 * casting it to ReactElement measured fine -- four containers, 330x260 each -- and rendered
 * NOTHING. Zero SVG elements, no console error, no warning. Each chart component below
 * therefore owns its own container, where its element is a literal child.
 *
 * The lesson is the one deferred.md §L.18 already records for the phone: compile green,
 * data green, screen blank. Only the screen can tell you.
 */
export function ChartBox({
  title,
  note,
  children,
}: {
  title: string;
  note?: string;
  children: React.ReactNode;
}) {
  return (
    <div className="panel" style={{ padding: 20 }}>
      <h2>{title}</h2>
      <div style={{ height: 260 }}>{children}</div>
      {note && (
        <p className="sub" style={{ marginTop: 12, lineHeight: 1.5 }}>
          {note}
        </p>
      )}
    </div>
  );
}

type Point = Record<string, string | number>;

export function MoneyLines({
  data,
  lines,
}: {
  data: Point[];
  lines: { key: string; name: string; color: string }[];
}) {
  return (
    <ResponsiveContainer width="100%" height="100%">
      <LineChart data={data} margin={{ top: 8, right: 8, bottom: 0, left: -2 }}>
        <CartesianGrid stroke={GRID} vertical={false} />
        <XAxis dataKey="label" {...AXIS} tickLine={false} axisLine={{ stroke: GRID }} />
        {/* Same narrowed axis as the bars -- see the note there. */}
        <YAxis
          {...AXIS}
          width={38}
          tickLine={false}
          axisLine={false}
          tickFormatter={moneyShort}
        />
        <Tooltip {...tooltipStyle} formatter={(value) => money(Number(value))} />
        <Legend wrapperStyle={{ fontSize: 12, color: '#8B92A4' }} />
        {lines.map((line) => (
          <Line
            key={line.key}
            type="monotone"
            dataKey={line.key}
            name={line.name}
            stroke={line.color}
            strokeWidth={2}
            dot={false}
          />
        ))}
      </LineChart>
    </ResponsiveContainer>
  );
}

export function MoneyBars({
  data,
  color = SERIES.primary,
  horizontal = false,
  unit = 'money',
  /**
   * Whether every category must keep its label.
   *
   * True for a handful of named buckets, where a dropped label leaves a bar pointing at
   * nothing. False for a twelve-month series, where Recharts thinning the axis is the
   * right call -- the months are ordered, so every other tick still reads.
   */
  allLabels = !horizontal && data.length <= 6,
}: {
  data: Point[];
  color?: string;
  horizontal?: boolean;
  unit?: 'money' | 'count';
  allLabels?: boolean;
}) {
  const format = unit === 'money' ? money : (value: number) => String(value);
  const tick = unit === 'money' ? moneyShort : (value: number) => String(value);

  return (
    <ResponsiveContainer width="100%" height="100%">
      <BarChart
        data={data}
        layout={horizontal ? 'vertical' : 'horizontal'}
        margin={{ top: 8, right: 8, bottom: 0, left: -2 }}
      >
        <CartesianGrid stroke={GRID} vertical={!horizontal} horizontal={horizontal} />
        {horizontal ? (
          <>
            <XAxis type="number" {...AXIS} tickLine={false} axisLine={false} tickFormatter={tick} />
            <YAxis
              type="category"
              dataKey="label"
              {...AXIS}
              tickLine={false}
              axisLine={false}
              width={84}
            />
          </>
        ) : (
          <>
            {/* interval={0} keeps EVERY category's label. Recharts thins them by default
                when it expects a collision, and on the five-bucket debt chart that dropped
                two of the five -- leaving bars that lined up with none of the labels still
                on screen. Found in a screenshot, not a test. */}
            <XAxis
              dataKey="label"
              {...AXIS}
              tickLine={false}
              axisLine={{ stroke: GRID }}
              interval={allLabels ? 0 : 'preserveStartEnd'}
              tickFormatter={(value: string) =>
                value.length > 12 ? `${value.slice(0, 11)}…` : value
              }
            />
            {/*
              width=38, not the default 60. The ticks here are short -- "24", "140 B" --
              and the default reserved almost three times the right-hand gap on the left,
              so every chart sat visibly off-centre in its card. Measured: 43px left
              against 14px right.
            */}
            <YAxis
              {...AXIS}
              width={38}
              tickLine={false}
              axisLine={false}
              tickFormatter={tick}
            />
          </>
        )}
        <Tooltip
          {...tooltipStyle}
          cursor={{ fill: 'rgba(91,135,255,0.08)' }}
          formatter={(value) => format(Number(value))}
        />
        <Bar dataKey="value" fill={color} radius={horizontal ? [0, 4, 4, 0] : [4, 4, 0, 0]} />
      </BarChart>
    </ResponsiveContainer>
  );
}

/**
 * A donut with its legend beside it rather than under it.
 *
 * Ten categories wrapped to three rows underneath, which ate a third of the frame and left
 * the ring small enough that the smaller slices were unreadable. On the side, the legend
 * gets one item per line and the ring keeps its height.
 */
export function Donut({ data }: { data: Point[] }) {
  return (
    <ResponsiveContainer width="100%" height="100%">
      {/*
        The ring sits in the left half, the legend in the right. cx is where its CENTRE
        goes, so it has to leave room for the radius on both sides of itself: at 32% with
        a 76% radius the left edge fell 12px outside the card and the ring came out
        clipped, while 185px of empty space sat on the right. 26% centres the ring inside
        the space the legend leaves it.
      */}
      <PieChart margin={{ top: 0, right: 0, bottom: 0, left: 0 }}>
        <Pie
          data={data}
          dataKey="value"
          nameKey="label"
          cx="36%"
          cy="50%"
          innerRadius="44%"
          outerRadius="70%"
          paddingAngle={2}
          stroke="none"
        >
          {data.map((_, index) => (
            <Cell key={index} fill={CATEGORICAL[index % CATEGORICAL.length]} />
          ))}
        </Pie>
        <Tooltip
          {...tooltipStyle}
          formatter={(value) => money(Number(value))}
          position={{ x: 0, y: 0 }}
          itemStyle={{ color: '#E8EAF0' }}
        />
        <Legend
          layout="vertical"
          align="right"
          verticalAlign="middle"
          iconSize={9}
          // The same weight and colour a table's row text gets. It was --muted grey at
          // 11px, which reads as a caption -- but these are the chart's labels, the way the
          // bar chart's axis ticks are, and a label you have to squint at is not a label.
          wrapperStyle={{ fontSize: 12, color: '#E8EAF0', lineHeight: '19px' }}
        />
      </PieChart>
    </ResponsiveContainer>
  );
}
