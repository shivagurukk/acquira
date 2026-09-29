import React from 'react';
import { useAuth } from '../contexts/AuthContext';

/**
 * Renders its children only when the current user holds a permission.
 *
 *   <Can perm="sales.agents" action="EDIT"><SaveButton /></Can>
 *   <Can perm="settlement.payment" action="APPROVE"><ReleaseButton /></Can>
 *
 * `perm` is the stable menu_key (`sales.agents`), not the route. Routes get
 * renamed; keying on the URL would silently revoke a control.
 *
 * This is presentation only. Every call the hidden control would have made is
 * still checked server-side by `@perm.can(...)` — hiding it stops the UI
 * offering an action it knows will be refused, it is not the access control.
 *
 * Pass `fallback` to show something in place of the hidden node (a disabled
 * button with a tooltip usually beats silence, because a control that simply
 * vanishes reads as a bug to the user).
 */
export function Can({ perm, action = 'VIEW', fallback = null, children }) {
    const { can } = useAuth();
    return can(perm, action) ? <>{children}</> : fallback;
}

/**
 * Route-level version of the same check, for screens rather than controls.
 * Renders `fallback` (default: nothing) when the user may not view the screen.
 *
 *   <PermGuard perm="sales.agents"><SalesAgents /></PermGuard>
 *
 * Replaces `RoleGuard requiredRoles={[...]}`: roles and group grants were two
 * orthogonal axes that could disagree, which is what let a user reach a screen
 * whose API refused them (and, less often, the reverse).
 */
export function PermGuard({ perm, action = 'VIEW', fallback = null, children }) {
    const { can } = useAuth();
    return can(perm, action) ? <>{children}</> : fallback;
}

export default Can;
