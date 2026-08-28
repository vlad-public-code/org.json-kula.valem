import type { ComponentSpec } from './types';
import { hasChildComponents } from './types';

/**
 * View-to-view navigation: binding a component tree to one array element, and spotting a view the
 * user cannot leave.
 *
 * Both exist because a `sectionList` can edit its elements in a **separate view** (`itemView`)
 * rather than inline. That mode used to be unusable: the navigation carried no index, so the item
 * view could not tell which element it was editing, and nothing led back out of it.
 */

/** Bind-carrying fields on a component. `dependsOn` names another field's path, so it scopes too. */
const PATH_FIELDS = ['bind', 'bindFrom', 'bindTo', 'dependsOn'] as const;

/**
 * Rewrites the array-scope wildcard in every path a component tree carries, binding the tree to
 * one concrete element: `$.debts[*].name` → `$.debts[2].name`.
 *
 * Bracket rather than dot notation (`debts.2.name`) because bracket is the canonical JsonPath
 * spelling the server documents, and — unlike a bare numeric segment — it is what
 * `CompiledModel.staticSchema` normalises back to `[*]` for its per-model schema cache. A dot index
 * reads and writes identically but gives a 5,000-element array 5,000 distinct cache keys.
 *
 * Only the FIRST wildcard is replaced: one level of scoping is what an item view expresses, and a
 * nested `$.debts[*].payments[*].amount` keeps its inner wildcard for a nested list to scope in turn.
 */
export function scopeToIndex(components: ComponentSpec[], index: number): ComponentSpec[] {
  return components.map(c => scopeOne(c, index));
}

function scopeOne(component: ComponentSpec, index: number): ComponentSpec {
  const out: Record<string, unknown> = { ...(component as Record<string, unknown>) };

  for (const field of PATH_FIELDS) {
    const value = out[field];
    if (typeof value === 'string' && value.includes('[*]')) {
      out[field] = substituteFirst(value, index);
    }
  }

  // keyValueList / summaryList rows hold their own binds and are not components.
  if (Array.isArray(out.items)) {
    out.items = out.items.map(row => {
      if (row == null || typeof row !== 'object') return row;
      const item = row as Record<string, unknown>;
      const bind = item.bind;
      if (typeof bind !== 'string' || !bind.includes('[*]')) return row;
      return { ...item, bind: substituteFirst(bind, index) };
    });
  }

  if (hasChildComponents(component) && component.components) {
    out.components = scopeToIndex(component.components, index);
  }

  return out as ComponentSpec;
}

function substituteFirst(path: string, index: number): string {
  return path.replace('[*]', `[${index}]`);
}

/**
 * True when this component tree offers the user a way to leave {@code viewId} — an authored
 * navigation control. Exactly the two components that call `onNavigate` with an authored target:
 * a `button` with an `onClick.navigate`, and a `menu` / `stepper` / `breadcrumb` `menuItem`.
 *
 * A `sectionList`'s `itemView` deliberately does not count: it navigates *into* an element, which
 * is a way further in, not a way out.
 */
export function hasNavigationAway(components: ComponentSpec[] | undefined, viewId: string): boolean {
  if (!components) return false;
  return components.some(c => {
    const rec = c as Record<string, unknown>;

    const onClick = rec.onClick as { navigate?: string } | undefined;
    if (onClick?.navigate && onClick.navigate !== viewId) return true;

    const items = rec.menuItems as Array<{ targetView?: string }> | undefined;
    if (items?.some(i => i.targetView && i.targetView !== viewId)) return true;

    return hasChildComponents(c) && hasNavigationAway(c.components, viewId);
  });
}
