// Icons the sidebar/topbar can render, as NAMED imports so Rollup tree-shakes
// the rest of lucide-react (`import * as` + a dynamic lookup pulled in the whole
// ~880 KB set on every cold load).
//
// Menu icons are looked up by sys_menu.icon_key. When a new screen uses an icon
// that is not listed here (rbac/menu-catalog.json or a menu migration), add it
// below — an unknown key falls back to Circle.
import {
    // menu icons (sys_menu.icon_key)
    Activity, AlertTriangle, ArrowDownToLine, ArrowUpFromLine, BarChart2, BarChart3,
    Bell, BellRing, BookOpen, BrainCircuit, Building, Cable, Calendar, CalendarClock,
    ClipboardList, Clock, Cloud, Code, Compass, Contact, CreditCard, Database,
    DatabaseZap, DollarSign, Factory, FileCode, FileText, FolderKanban, Gauge, Globe,
    Grid, HardDrive, HeartHandshake, Landmark, Layers, LayoutDashboard, LayoutGrid,
    List, Mail, MailOpen, Network, PackageOpen, PieChart, Presentation, Receipt,
    Scale, ScrollText, Settings, Shield, ShieldAlert, ShieldCheck, SlidersHorizontal,
    Store, Table, Target, TrendingDown, TrendingUp, Trophy, Upload, Users,
    // layout chrome
    Circle, KeyRound, LogOut, Menu, Moon, MoreHorizontal, PanelLeftClose,
    PanelLeftOpen, Search, Sun, X,
} from 'lucide-react';

export const Icons = {
    Activity, AlertTriangle, ArrowDownToLine, ArrowUpFromLine, BarChart2, BarChart3,
    Bell, BellRing, BookOpen, BrainCircuit, Building, Cable, Calendar, CalendarClock,
    ClipboardList, Clock, Cloud, Code, Compass, Contact, CreditCard, Database,
    DatabaseZap, DollarSign, Factory, FileCode, FileText, FolderKanban, Gauge, Globe,
    Grid, HardDrive, HeartHandshake, Landmark, Layers, LayoutDashboard, LayoutGrid,
    List, Mail, MailOpen, Network, PackageOpen, PieChart, Presentation, Receipt,
    Scale, ScrollText, Settings, Shield, ShieldAlert, ShieldCheck, SlidersHorizontal,
    Store, Table, Target, TrendingDown, TrendingUp, Trophy, Upload, Users,
    Circle, KeyRound, LogOut, Menu, Moon, MoreHorizontal, PanelLeftClose,
    PanelLeftOpen, Search, Sun, X,
};
