import React, { useState, useRef, useEffect } from 'react';
import { Check, ChevronDown, X, Trash2 } from 'lucide-react';

const MultiSelect = ({ label, options, selectedValues, onChange, placeholder = "Select..." }) => {
    const [isOpen, setIsOpen] = useState(false);
    const wrapperRef = useRef(null);

    useEffect(() => {
        function handleClickOutside(event) {
            if (wrapperRef.current && !wrapperRef.current.contains(event.target)) {
                setIsOpen(false);
            }
        }
        document.addEventListener("mousedown", handleClickOutside);
        return () => {
            document.removeEventListener("mousedown", handleClickOutside);
        };
    }, [wrapperRef]);

    const handleSelect = (option) => {
        let newValues;
        if (selectedValues.includes(option)) {
            newValues = selectedValues.filter(v => v !== option);
        } else {
            newValues = [...selectedValues, option];
        }
        onChange(newValues);
    };

    const removeValue = (e, val) => {
        e.stopPropagation();
        onChange(selectedValues.filter(v => v !== val));
    };

    const clearAll = (e) => {
        e.stopPropagation();
        onChange([]);
    };

    return (
        <div className={`relative ${isOpen ? 'z-50' : ''}`} ref={wrapperRef}>
            {label && <label className="block text-xs font-bold text-slate-500 mb-1">{label}</label>}
            <div
                onClick={() => setIsOpen(!isOpen)}
                className={`
                    w-full min-h-[38px] px-3 py-1 bg-white border border-slate-200 rounded-lg cursor-pointer
                    flex items-center justify-between transition-all shadow-sm
                    hover:border-blue-400
                    ${isOpen ? 'ring-2 ring-blue-100 border-blue-400' : ''}
                `}
            >
                <div className="flex gap-1 flex-wrap items-center overflow-hidden">
                    {selectedValues.length === 0 && <span className="text-slate-400 text-sm py-1">{placeholder}</span>}

                    {/* Show first 2 items */}
                    {selectedValues.slice(0, 2).map(val => (
                        <div key={val} className="bg-blue-50 text-blue-700 text-xs px-2 py-1 rounded flex items-center gap-1 border border-blue-100 whitespace-nowrap">
                            <span className="truncate max-w-[80px]">{val}</span>
                            <span onClick={(e) => removeValue(e, val)} className="hover:text-blue-900 cursor-pointer rounded-full hover:bg-blue-100 p-0.5">
                                <X size={10} />
                            </span>
                        </div>
                    ))}

                    {/* Show count if more than 2 */}
                    {selectedValues.length > 2 && (
                        <span className="text-xs font-bold text-blue-600 bg-blue-100 px-1.5 py-0.5 rounded-full">
                            +{selectedValues.length - 2}
                        </span>
                    )}
                </div>

                <div className="flex items-center gap-1">
                    {selectedValues.length > 0 && (
                        <div
                            onClick={clearAll}
                            className="p-1 hover:bg-red-50 text-slate-300 hover:text-red-500 rounded transition-colors mr-1"
                            title="Clear all"
                        >
                            <X size={14} />
                        </div>
                    )}
                    <ChevronDown size={14} className={`text-slate-400 transition-transform ${isOpen ? 'rotate-180' : ''}`} />
                </div>
            </div>

            {isOpen && (
                <div className="absolute top-full left-0 w-full mt-1 bg-white border border-slate-200 rounded-lg shadow-2xl z-[100] max-h-60 overflow-y-auto py-1">
                    {options.map(opt => (
                        <div
                            key={opt}
                            onClick={() => handleSelect(opt)}
                            className={`
                                px-3 py-2 cursor-pointer flex items-center justify-between text-sm transition-colors
                                ${selectedValues.includes(opt) ? 'bg-blue-50 text-blue-700 font-medium' : 'text-slate-700 hover:bg-slate-50'}
                            `}
                        >
                            <span>{opt}</span>
                            {selectedValues.includes(opt) && <Check size={14} className="text-blue-600" />}
                        </div>
                    ))}
                    {options.length === 0 && (
                        <div className="px-3 py-4 text-center text-slate-400 text-sm italic">No options available</div>
                    )}
                </div>
            )}
        </div>
    );
};

export default MultiSelect;
