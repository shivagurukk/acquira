package com.acquira.common.dto;

import java.util.ArrayList;
import java.util.List;

public class MenuDTO {
    private String name;
    private String path;
    private String icon;
    private List<MenuDTO> children = new ArrayList<>();

    public MenuDTO(String name, String path, String icon) {
        this.name = name;
        this.path = path;
        this.icon = icon;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getIcon() {
        return icon;
    }

    public void setIcon(String icon) {
        this.icon = icon;
    }

    public List<MenuDTO> getChildren() {
        return children;
    }

    public void setChildren(List<MenuDTO> children) {
        this.children = children;
    }

    public void addChild(MenuDTO child) {
        this.children.add(child);
    }
}
