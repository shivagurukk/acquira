import React from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import { Check, ChevronDown, Lock, Filter, RefreshCcw, Calendar, Search, ArrowRight, X } from 'lucide-react';

// --- 1. Cockpit Segmented Control (High Contrast Pills) ---
export const CockpitSegmentedControl = ({ label, options, value, onChange, disabled }) => (
    <div className={`flex flex-col gap-2 ${disabled ? 'opacity-50 pointer-events-none' : ''}`}>
        {label && <span className="text-xs font-bold text-slate-500 uppercase tracking-widest ml-1">{label}</span>}
        <div className="flex flex-wrap gap-2">
            {options.map((opt) => {
                const isActive = value === opt.value;
                return (
                    <button
                        key={opt.value}
                        onClick={() => onChange(opt.value)}
                        className={`
                            relative px-5 py-2.5 rounded-full text-xs font-bold transition-all border
                            ${isActive
                                ? 'bg-indigo-600 border-indigo-600 text-white shadow-md shadow-indigo-600/20'
                                : 'bg-white border-slate-200 text-slate-600 hover:border-indigo-300 hover:text-indigo-600'
                            }
                        `}
                    >
                        {opt.label}
                    </button>
                );
            })}
        </div>
    </div>
);

// --- 2. Smart Empty State (Action Oriented) ---
export const SmartEmptyState = ({ onReset, onExpandRange }) => (
    <div className="flex flex-col items-center justify-center py-20 px-4 text-center">
        <div className="bg-slate-50 p-6 rounded-full mb-6 border border-slate-100 shadow-inner">
            <Search size={32} className="text-slate-300" />
        </div>
        <h3 className="text-lg font-bold text-slate-800 mb-2">No transactions found</h3>
        <p className="text-slate-500 max-w-md mb-8 leading-relaxed">
            This could be due to a restrictive date range or specific filter combinations.
        </p>

        <div className="flex flex-col sm:flex-row gap-4 w-full max-w-md">
            <button
                onClick={onExpandRange}
                className="flex-1 flex items-center justify-center gap-2 px-5 py-3 bg-white border border-slate-200 rounded-xl text-slate-700 font-bold text-sm hover:bg-slate-50 hover:border-indigo-300 transition-all shadow-sm"
            >
                <Calendar size={16} /> Expand Date Range
            </button>
            <button
                onClick={onReset}
                className="flex-1 flex items-center justify-center gap-2 px-5 py-3 bg-indigo-50 border border-indigo-100 rounded-xl text-indigo-700 font-bold text-sm hover:bg-indigo-100 transition-all shadow-sm"
            >
                <RefreshCcw size={16} /> Reset Filters
            </button>
        </div>
    </div>
);

// --- 3. Query Summary (Trust Builder) ---
export const QuerySummary = ({ activeFilters }) => {
    // Simplify implementation for now, expects an array of strings
    if (!activeFilters || activeFilters.length === 0) return null;

    return (
        <div className="flex items-center flex-wrap gap-2 text-xs font-medium text-slate-500 mb-4 px-1">
            <span className="font-bold text-slate-400 uppercase tracking-wider mr-2">Query Summary:</span>
            {activeFilters.map((filter, index) => (
                <React.Fragment key={index}>
                    <span className="text-indigo-600 bg-indigo-50 border border-indigo-100 px-2 py-0.5 rounded-md">
                        {filter}
                    </span>
                    {index < activeFilters.length - 1 && <span className="text-slate-300">•</span>}
                </React.Fragment>
            ))}
        </div>
    );
};

// --- 4. Smart Dependent Filter (Progressive Disclosure) ---
export const CockpitFilter = ({ label, placeholder = "Select...", value = [], onChange, options = [], disabled, dependencyText }) => {
    const [isOpen, setIsOpen] = React.useState(false);
    const wrapperRef = React.useRef(null);

    React.useEffect(() => {
        const handleClick = (e) => {
            if (wrapperRef.current && !wrapperRef.current.contains(e.target)) setIsOpen(false);
        };
        document.addEventListener('mousedown', handleClick);
        return () => document.removeEventListener('mousedown', handleClick);
    }, []);

    const selectedCount = value.length;
    const isAllSelected = selectedCount === 0; // "All" logic

    // Lock State
    if (disabled) {
        return (
            <div className="relative w-full opacity-60">
                <label className="block text-xs font-bold text-slate-400 uppercase tracking-widest mb-2 ml-1">{label}</label>
                <div className="w-full px-4 py-3 bg-slate-50 border border-slate-200 border-dashed rounded-xl flex items-center justify-between cursor-not-allowed">
                    <span className="text-slate-400 text-sm italic flex items-center gap-2">
                        <Lock size={14} /> {dependencyText || "Locked"}
                    </span>
                </div>
            </div>
        );
    }

    const toggleOption = (val) => {
        const newValue = value.includes(val) ? value.filter(v => v !== val) : [...value, val];
        onChange(newValue);
    };

    return (
        <div className="relative w-full group" ref={wrapperRef}>
            <label className="block text-xs font-bold text-slate-500 uppercase tracking-widest mb-2 ml-1">{label}</label>
            <div
                onClick={() => setIsOpen(!isOpen)}
                className={`
                    w-full px-4 py-3 bg-white border rounded-xl flex items-center justify-between cursor-pointer transition-all shadow-sm
                    ${isOpen ? 'border-indigo-500 ring-2 ring-indigo-500/10' : 'border-slate-200 hover:border-indigo-300'}
                `}
            >
                <div className="flex-1 truncate text-slate-700 font-medium text-sm pr-2">
                    {isAllSelected ? (
                        <span className="text-slate-400 font-normal">{placeholder}</span>
                    ) : (
                        <span className="text-indigo-600 bg-indigo-50 px-2 py-0.5 rounded text-xs font-bold">
                            {selectedCount} Selected
                        </span>
                    )}
                </div>
                <div className="flex items-center gap-1">
                    {!isAllSelected && (
                        <button
                            onClick={(e) => { e.stopPropagation(); onChange([]); }}
                            className="p-1 hover:bg-slate-100 rounded-full text-slate-400 hover:text-red-500 transition-colors"
                        >
                            <X size={14} />
                        </button>
                    )}
                    <ChevronDown size={16} className={`text-slate-400 transition-transform ${isOpen ? 'rotate-180 text-indigo-500' : ''}`} />
                </div>
            </div>

            <AnimatePresence>
                {isOpen && (
                    <motion.div
                        initial={{ opacity: 0, y: 8, scale: 0.98 }}
                        animate={{ opacity: 1, y: 0, scale: 1 }}
                        exit={{ opacity: 0, y: 8, scale: 0.98 }}
                        className="absolute z-50 mt-2 w-full bg-white border border-slate-100 rounded-xl shadow-xl overflow-hidden ring-1 ring-black/5"
                    >
                        <div className="max-h-60 overflow-y-auto custom-scrollbar p-1">
                            {options.length === 0 ? (
                                <div className="p-4 text-xs text-center text-slate-400">No options found</div>
                            ) : (
                                options.map(opt => (
                                    <div
                                        key={opt.value}
                                        onClick={() => toggleOption(opt.value)}
                                        className={`flex items-center gap-3 px-3 py-2.5 rounded-lg text-sm cursor-pointer transition-all ${value.includes(opt.value) ? 'bg-indigo-50 text-indigo-700 font-medium' : 'text-slate-600 hover:bg-slate-50'}`}
                                    >
                                        <div className={`w-4 h-4 rounded border flex items-center justify-center ${value.includes(opt.value) ? 'bg-indigo-600 border-indigo-600' : 'border-slate-300 bg-white'}`}>
                                            {value.includes(opt.value) && <Check size={10} className="text-white" />}
                                        </div>
                                        <span className="truncate">{opt.label}</span>
                                    </div>
                                ))
                            )}
                        </div>
                    </motion.div>
                )}
            </AnimatePresence>
        </div>
    );
};
