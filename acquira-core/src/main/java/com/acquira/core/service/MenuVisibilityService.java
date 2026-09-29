package com.acquira.core.service;

import com.acquira.common.model.SysMenu;
import com.acquira.common.repository.SysMenuRepository;
import com.acquira.common.security.PermissionEvaluator;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The sidebar, resolved from the same rule as every API guard.
 *
 * <p>Three places used to build the menu list independently — login, tenant
 * switch, and {@code GET /me/menus} — and all three read the tenant-blind
 * {@code sys_group_menu} join. That is how a screen could appear in the sidebar
 * and then 403 on click, or be hidden while its API stayed callable. They all
 * come through here now, so "what you can see" and "what you can call" are one
 * answer.
 */
@Service
public class MenuVisibilityService {

    private final SysMenuRepository menuRepository;
    private final PermissionEvaluator perm;

    public MenuVisibilityService(SysMenuRepository menuRepository, PermissionEvaluator perm) {
        this.menuRepository = menuRepository;
        this.perm = perm;
    }

    /** Screens {@code username} may open in {@code tenantId}, in sidebar order. */
    public List<SysMenu> visibleMenus(String username, Integer tenantId, boolean superAdmin) {
        if (username == null || tenantId == null) return List.of();
        return filter(perm.permissionsFor(username, tenantId, superAdmin));
    }

    /** Screens the current authenticated caller may open, in sidebar order. */
    public List<SysMenu> visibleMenusForCurrentUser() {
        return filter(perm.currentPermissions());
    }

    private List<SysMenu> filter(Set<String> permissions) {
        if (permissions == null || permissions.isEmpty()) return List.of();
        List<SysMenu> all = menuRepository.findAllByOrderByDisplayOrderAsc();
        List<SysMenu> visible = new ArrayList<>(all.size());
        for (SysMenu m : all) {
            String key = PermissionEvaluator.keyOf(m.getPath());
            if (key != null && permissions.contains(key + ":VIEW")) {
                visible.add(m);
            }
        }
        return visible;
    }
}
