# Acquira UI kit

Token-driven primitives for admin surfaces. Everything resolves through the CSS
variables in `src/index.css`, so light and dark both work with no per-component
overrides.

```jsx
import { Page, Card, Button, DataTable, Modal, FormField, Input } from '../../components/ui';
```

**Rule of thumb:** if you're about to write `style={{}}` for a button, table,
modal, tab strip, badge or form control, the kit already has it. Reach for
inline styles only for genuinely one-off layout.

`Page` and `DataTable` are MUI-free. Do not add `@mui/*` imports to migrated
pages — MUI retirement is the end state of the frontend cleanup.

---

## Page

Standard shell: max width, padding, and the title/subtitle/actions header.

```jsx
<Page title="Tenant management"
      subtitle="Financial institutions and jurisdictions."
      icon={Building2}
      actions={<Button variant="primary" icon={Plus}>Add entity</Button>}>
  …
</Page>
```

| Prop | Values |
|---|---|
| `width` | `default` (1440px) · `narrow` (960px) · `wide` (uncapped) |
| `flush` | drop padding when embedded in another shell (e.g. SettingsHub) |

`Stack` (vertical rhythm, `gap="sm" \| "md" \| "lg"`) and `Row`
(horizontal group, `between` for space-between) are exported alongside it.

## Card

```jsx
<Card title="SMTP" subtitle="Outbound mail" actions={<Button size="sm">Test</Button>} pad>
  …
</Card>
```

Omit `pad` when the child is a `DataTable` — the table manages its own edges.
For full control compose `CardHeader` / `CardBody` / `CardFooter` directly.

## Button

```jsx
<Button variant="primary" icon={Save} loading={saving}>Save</Button>
<Button variant="danger-ghost" size="sm" onClick={remove}>Delete</Button>
<Button variant="ghost" iconOnly icon={Edit2} aria-label="Edit user" />
```

- `variant`: `default` · `primary` · `danger` · `ghost` · `subtle` · `danger-ghost`
- `size`: `sm` · `md` · `lg`
- `loading` shows a spinner and disables the button
- `iconOnly` requires an `aria-label`
- `to` renders a router `Link`, `href` renders an anchor

## DataTable

Owns sorting, the loading skeleton and the empty state. Define columns, pass rows.

```jsx
<DataTable
  columns={[
    { key: 'name',   header: 'Name',   sortable: true },
    { key: 'status', header: 'Status', render: r => <StatusBadge status={r.status} /> },
    { key: 'amount', header: 'Amount', align: 'right', numeric: true },
    { key: '_actions', header: '', align: 'right',
      render: r => <Button size="sm" onClick={() => edit(r)}>Edit</Button> },
  ]}
  rows={rows}
  rowKey={r => r.id}
  loading={loading}
  defaultSort={{ key: 'name', dir: 'asc' }}
  search={{ value: q, onChange: setQ, placeholder: 'Search users…' }}
/>
```

Column options: `key` · `header` · `render(row, i)` · `sortValue(row)` ·
`sortable` · `align` · `numeric` · `mono` · `muted` · `nowrap` · `width`.

Table options: `loading` · `skeletonRows` · `empty` (node) · `emptyVariant` ·
`onRowClick` · `search` · `toolbarLeft` / `toolbarRight` · `compact` ·
`stickyHeader` · `defaultSort` · `pageSize` · `rowClassName`.

Filtering stays on the page — pass already-filtered `rows`. The table sorts and
(opt-in) paginates.

**Pagination** — set `pageSize` and the table renders its own pager footer:

```jsx
<DataTable columns={cols} rows={history} pageSize={10} />
```

The current page is clamped, so filtering down to a shorter list can never
strand the view on a page that no longer exists.

**Row styling** — `rowClassName(row, i)` marks rows visually:

```jsx
<DataTable … rowClassName={r => r.revoked && 'ui-tr--dimmed'} />
```

## Modal

Portalled, focus-trapped, Escape-to-close, scroll-locked, restores focus.

```jsx
<Modal as="form" onSubmit={save}
       open={open} onClose={close}
       title="New entity" size="lg"
       footer={<>
         <Button type="button" onClick={close}>Cancel</Button>
         <Button type="submit" variant="primary" loading={saving}>Save</Button>
       </>}>
  …fields…
</Modal>
```

`as="form"` makes the footer's `type="submit"` button submit. `size`: `sm` ·
`md` · `lg` · `xl`.

## ConfirmDialog — replaces `window.confirm`

`ConfirmProvider` is mounted in `App.jsx`. Awaitable:

```jsx
const confirm = useConfirm();

const remove = async (row) => {
  const ok = await confirm({
    title: 'Delete API key?',
    message: 'Any integration using this key stops working immediately.',
    confirmLabel: 'Delete key',
    tone: 'danger',            // danger | warning | info
  });
  if (!ok) return;
  …
};
```

## Forms

```jsx
<FormGrid cols={2}>
  <FormField label="Host" required hint="Hostname only, no scheme" error={errors.host}>
    <Input value={host} onChange={e => setHost(e.target.value)} />
  </FormField>
  <FormField label="Encryption">
    <Select value={enc} onChange={…} placeholder="Select…"
            options={[{ value: 'tls', label: 'TLS' }, { value: 'ssl', label: 'SSL' }]} />
  </FormField>
</FormGrid>

<Switch checked={enabled} onChange={e => setEnabled(e.target.checked)} label="Enable SSO" />
<Checkbox checked={v} onChange={…} label="Send a copy" hint="Goes to the account owner" />
```

`FormField` injects `id`, `aria-describedby` and `aria-invalid` into its child —
don't set them by hand. `Textarea` takes `mono` for SQL/JSON. Grid helpers:
`ui-form-grid--span` (2 cols), `--span-3`, `--span-all`.

`Switch` is for settings that apply immediately; `Checkbox` for values inside a form.

## Badge / StatusBadge / Alert

```jsx
<Badge tone="brand" mono>AGB</Badge>
<StatusBadge status={row.status} />   {/* maps SUCCESS/FAILED/PENDING/… to a tone */}

<Alert tone="warning" title="Nightly window is open">
  Maintenance runs at 02:00 UTC.
</Alert>
```

`Badge` tones: `neutral` · `success` · `warning` · `danger` · `info` · `brand`.
Use `Alert` for persistent page context; use a toast for transient feedback.

## Tabs

```jsx
<Tabs
  tabs={[{ key: 'scripts', label: 'Scripts', icon: FileCode, count: 4 }, …]}
  active={tab} onChange={setTab}
/>
```

`variant="pills"` for tabs nested inside a card. Arrow keys move between tabs.

## Feedback

Transient feedback goes through the toast, never `alert()`:

```jsx
import { showToast } from '../../contexts/ToastContext';

showToast('Settings saved', 'success');
showToast(err?.response?.data?.error || 'Save failed', 'error');
```

Types: `success` · `error` · `warning` · `info`.
