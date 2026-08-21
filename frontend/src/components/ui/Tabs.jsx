import React from 'react';

/**
 * Tabs — controlled tab strip.
 *
 *   <Tabs
 *     tabs={[{ key: 'scripts', label: 'Provision scripts', icon: FileCode, count: 4 }]}
 *     active={tab}
 *     onChange={setTab}
 *   />
 *
 * variant: 'underline' (default, page-level) | 'pills' (inside a card)
 * Set `inline` when the strip sits in a toolbar row beside other controls —
 * it drops the page-level bottom margin that would otherwise misalign it.
 * Arrow keys move between tabs, matching the ARIA tabs pattern.
 */
export default function Tabs({
  tabs = [],
  active,
  onChange,
  variant = 'underline',
  inline = false,
  className = '',
}) {
  const enabled = tabs.filter((t) => !t.disabled);

  const onKeyDown = (e) => {
    const dir = e.key === 'ArrowRight' ? 1 : e.key === 'ArrowLeft' ? -1 : 0;
    if (!dir) return;
    e.preventDefault();
    const i = enabled.findIndex((t) => t.key === active);
    const next = enabled[(i + dir + enabled.length) % enabled.length];
    if (next) onChange?.(next.key);
  };

  return (
    <div
      role="tablist"
      onKeyDown={onKeyDown}
      className={[
        'ui-tabs',
        variant === 'pills' && 'ui-tabs--pills',
        inline && 'ui-tabs--inline',
        className,
      ]
        .filter(Boolean)
        .join(' ')}
    >
      {tabs.map((tab) => {
        const isActive = tab.key === active;
        const Icon = tab.icon;
        return (
          <button
            key={tab.key}
            role="tab"
            type="button"
            aria-selected={isActive}
            tabIndex={isActive ? 0 : -1}
            disabled={tab.disabled}
            onClick={() => onChange?.(tab.key)}
            className={`ui-tab ${isActive ? 'ui-tab--active' : ''}`}
          >
            {Icon && <Icon size={15} strokeWidth={2} />}
            {tab.label}
            {tab.count != null && <span className="ui-tab__count">{tab.count}</span>}
          </button>
        );
      })}
    </div>
  );
}
