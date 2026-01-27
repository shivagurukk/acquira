import React, { useState, useRef, useEffect } from 'react';
import { Calendar, ChevronLeft, ChevronRight, X } from 'lucide-react';

const DateRangePicker = ({ label, startDate, endDate, onChange }) => {
    const [isOpen, setIsOpen] = useState(false);
    const [currentMonth, setCurrentMonth] = useState(new Date());
    const [selectingStart, setSelectingStart] = useState(true);
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

    const formatDate = (dateString) => {
        if (!dateString) return '';
        const date = new Date(dateString);
        return date.toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' });
    };

    const getDaysInMonth = (date) => {
        const year = date.getFullYear();
        const month = date.getMonth();
        const days = new Date(year, month + 1, 0).getDate();
        const firstDay = new Date(year, month, 1).getDay();
        const result = [];

        for (let i = 0; i < firstDay; i++) {
            result.push(null);
        }

        for (let i = 1; i <= days; i++) {
            result.push(new Date(year, month, i));
        }

        return result;
    };

    const isDateSelected = (date) => {
        if (!date) return false;
        const s = startDate ? new Date(startDate).setHours(0, 0, 0, 0) : null;
        const e = endDate ? new Date(endDate).setHours(0, 0, 0, 0) : null;
        const d = date.setHours(0, 0, 0, 0);
        return d === s || d === e;
    };

    const isDateInRange = (date) => {
        if (!startDate || !endDate || !date) return false;
        const s = new Date(startDate).setHours(0, 0, 0, 0);
        const e = new Date(endDate).setHours(0, 0, 0, 0);
        const d = date.setHours(0, 0, 0, 0);
        return d > s && d < e;
    };

    const handleDateClick = (date) => {
        if (!date) return;

        // Fix for timezone issues: construct YYYY-MM-DD from local components
        const year = date.getFullYear();
        const month = String(date.getMonth() + 1).padStart(2, '0');
        const day = String(date.getDate()).padStart(2, '0');
        const dateStr = `${year}-${month}-${day}`;

        if (selectingStart) {
            // Update Start Date
            // Only clear End Date if it exists AND is before the new Start Date
            if (endDate && new Date(dateStr) > new Date(endDate)) {
                onChange(dateStr, '');
                setSelectingStart(false); // Valid switch: Start > End, so force re-pick End
            } else {
                onChange(dateStr, endDate); // Keep existing end date
                // Do NOT auto-switch to "To Date" if we just clicked "From Date" explicitly
                // Let user decide to click "To Date" or just stay here.
                // Actually, standard behavior is usually to switch, but user feedback says otherwise.
                // Let's keep focus on Start if that's what they selected.
                setSelectingStart(true);
            }
        } else {
            // Update End Date
            if (startDate && new Date(dateStr) < new Date(startDate)) {
                // If picking End Date but it's before Start Date, treat as new Start Date
                onChange(dateStr, '');
                setSelectingStart(false); // Switch to pick End Date now
            } else {
                onChange(startDate, dateStr);
                setIsOpen(false); // Close on complete selection
            }
        }
    };

    const clearDates = (e) => {
        e.stopPropagation();
        onChange('', '');
        setSelectingStart(true);
    };

    const nextMonth = () => {
        setCurrentMonth(new Date(currentMonth.getFullYear(), currentMonth.getMonth() + 1, 1));
    };

    const prevMonth = () => {
        setCurrentMonth(new Date(currentMonth.getFullYear(), currentMonth.getMonth() - 1, 1));
    };

    const days = getDaysInMonth(currentMonth);
    const months = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December'];

    return (
        <div className="relative" ref={wrapperRef}>
            <label className="block text-xs font-bold text-slate-500 mb-1">{label}</label>
            <div className="flex items-center gap-2">
                <div
                    onClick={() => { setSelectingStart(true); setIsOpen(true); }}
                    className={`
                        w-1/2 px-3 py-2 bg-white border rounded-lg cursor-pointer
                        flex items-center justify-between transition-all duration-200
                        hover:border-blue-400 hover:shadow-sm
                        ${isOpen && selectingStart ? 'ring-2 ring-blue-100 border-blue-400' : 'border-slate-200'}
                    `}
                >
                    <span className={`text-sm ${!startDate ? 'text-slate-400' : 'text-slate-700'}`}>
                        {startDate ? formatDate(startDate) : 'From Date'}
                    </span>
                    <Calendar size={14} className="text-slate-400" />
                </div>

                <div
                    onClick={() => { setSelectingStart(false); setIsOpen(true); }}
                    className={`
                        w-1/2 px-3 py-2 bg-white border rounded-lg cursor-pointer
                        flex items-center justify-between transition-all duration-200
                        hover:border-blue-400 hover:shadow-sm
                        ${isOpen && !selectingStart ? 'ring-2 ring-blue-100 border-blue-400' : 'border-slate-200'}
                    `}
                >
                    <span className={`text-sm ${!endDate ? 'text-slate-400' : 'text-slate-700'}`}>
                        {endDate ? formatDate(endDate) : 'To Date'}
                    </span>
                    {endDate ? (
                        <div onClick={clearDates} className="p-0.5 hover:bg-slate-100 rounded-full text-slate-400 hover:text-red-500">
                            <X size={14} />
                        </div>
                    ) : (
                        <Calendar size={14} className="text-slate-400" />
                    )}
                </div>
            </div>

            {isOpen && (
                <div className="absolute top-full left-0 mt-2 bg-white rounded-lg shadow-2xl border border-slate-200 z-[9999] p-4 w-72">
                    <div className="flex items-center justify-between mb-4">
                        <button onClick={prevMonth} className="p-1 hover:bg-slate-100 rounded-full text-slate-600">
                            <ChevronLeft size={18} />
                        </button>
                        <span className="font-semibold text-slate-800">
                            {months[currentMonth.getMonth()]} {currentMonth.getFullYear()}
                        </span>
                        <button onClick={nextMonth} className="p-1 hover:bg-slate-100 rounded-full text-slate-600">
                            <ChevronRight size={18} />
                        </button>
                    </div>

                    <div className="grid grid-cols-7 gap-1 mb-2">
                        {['Su', 'Mo', 'Tu', 'We', 'Th', 'Fr', 'Sa'].map(d => (
                            <div key={d} className="text-center text-xs font-medium text-slate-400 py-1">
                                {d}
                            </div>
                        ))}
                    </div>

                    <div className="grid grid-cols-7 gap-1">
                        {days.map((date, i) => {
                            const isSelected = isDateSelected(date);
                            const inRange = isDateInRange(date);
                            const isStart = date && startDate && date.toDateString() === new Date(startDate).toDateString();
                            const isEnd = date && endDate && date.toDateString() === new Date(endDate).toDateString();

                            let roundedClass = 'rounded-full';
                            if (inRange) roundedClass = 'rounded-none';
                            if (isStart && endDate) roundedClass = 'rounded-l-full rounded-r-none';
                            if (isEnd && startDate) roundedClass = 'rounded-r-full rounded-l-none';

                            return (
                                <button
                                    key={i}
                                    onClick={() => handleDateClick(date)}
                                    disabled={!date}
                                    className={`
                                        h-8 w-full flex items-center justify-center text-sm transition-all relative
                                        ${!date ? 'invisible' : ''}
                                        ${roundedClass}
                                        ${isSelected ? 'bg-blue-600 text-white font-bold shadow-sm z-10' : ''}
                                        ${inRange ? 'bg-blue-50 text-blue-700' : ''}
                                        ${!isSelected && !inRange && date ? 'text-slate-700 hover:bg-slate-100 rounded-full' : ''}
                                    `}
                                >
                                    {date ? date.getDate() : ''}
                                </button>
                            );
                        })}
                    </div>

                    <div className="mt-4 flex justify-between items-center text-xs border-t border-slate-100 pt-3">
                        <span className="text-slate-500 font-medium whitespace-nowrap">
                            {selectingStart ? 'Selecting: From Date' : 'Selecting: To Date'}
                        </span>
                        <button
                            onClick={() => setIsOpen(false)}
                            className="text-blue-600 font-medium hover:text-blue-700 hover:underline px-2"
                        >
                            Done
                        </button>
                    </div>
                </div>
            )}
        </div>
    );
};

export default DateRangePicker;
