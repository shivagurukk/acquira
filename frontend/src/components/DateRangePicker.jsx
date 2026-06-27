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
        <div className="drp-wrap" ref={wrapperRef}>
            <label className="drp-label">{label}</label>
            <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                <div
                    onClick={() => { setSelectingStart(true); setIsOpen(true); }}
                    className={`drp-trigger${isOpen && selectingStart ? ' active' : ''}`}
                >
                    <span style={{ color: startDate ? 'var(--text)' : 'var(--text-muted)' }}>
                        {startDate ? formatDate(startDate) : 'From Date'}
                    </span>
                    <Calendar size={14} style={{ color: 'var(--text-muted)', flexShrink: 0 }} />
                </div>

                <div
                    onClick={() => { setSelectingStart(false); setIsOpen(true); }}
                    className={`drp-trigger${isOpen && !selectingStart ? ' active' : ''}`}
                >
                    <span style={{ color: endDate ? 'var(--text)' : 'var(--text-muted)' }}>
                        {endDate ? formatDate(endDate) : 'To Date'}
                    </span>
                    {endDate ? (
                        <span onClick={clearDates} className="drp-clear"><X size={13} /></span>
                    ) : (
                        <Calendar size={14} style={{ color: 'var(--text-muted)', flexShrink: 0 }} />
                    )}
                </div>
            </div>

            {isOpen && (
                <div className="drp-pop">
                    <div className="drp-pop-head">
                        <button onClick={prevMonth} className="drp-nav"><ChevronLeft size={17} /></button>
                        <span style={{ fontWeight: 600, fontSize: '0.88rem', color: 'var(--text)' }}>
                            {months[currentMonth.getMonth()]} {currentMonth.getFullYear()}
                        </span>
                        <button onClick={nextMonth} className="drp-nav"><ChevronRight size={17} /></button>
                    </div>

                    <div className="drp-grid" style={{ marginBottom: 4 }}>
                        {['Su', 'Mo', 'Tu', 'We', 'Th', 'Fr', 'Sa'].map(d => (
                            <div key={d} className="drp-dow">{d}</div>
                        ))}
                    </div>

                    <div className="drp-grid">
                        {days.map((date, i) => {
                            if (!date) return <div key={i} />;
                            const isSelected = isDateSelected(date);
                            const inRange = isDateInRange(date);
                            let cls = 'drp-day';
                            if (inRange) cls += ' range';
                            if (isSelected) cls += ' sel';
                            return (
                                <button key={i} onClick={() => handleDateClick(date)} className={cls}>
                                    {date.getDate()}
                                </button>
                            );
                        })}
                    </div>

                    <div className="drp-foot">
                        <span style={{ color: 'var(--text-secondary)', fontWeight: 500, whiteSpace: 'nowrap' }}>
                            {selectingStart ? 'Selecting: From Date' : 'Selecting: To Date'}
                        </span>
                        <button onClick={() => setIsOpen(false)} className="drp-done">Done</button>
                    </div>
                </div>
            )}

            <style>{`
                .drp-wrap { position: relative; }
                .drp-label {
                    display: block; font-size: 0.72rem; font-weight: 700; text-transform: uppercase;
                    letter-spacing: 0.04em; color: var(--text-muted); margin-bottom: 6px;
                }
                .drp-trigger {
                    width: 50%; padding: 8px 11px; background: var(--bg-card);
                    border: 1px solid var(--border); border-radius: var(--radius-md); cursor: pointer;
                    display: flex; align-items: center; justify-content: space-between; gap: 6px;
                    font-size: 0.82rem; transform: none;
                    transition: border-color 0.15s, box-shadow 0.15s;
                }
                .drp-trigger:hover { border-color: var(--brand); }
                .drp-trigger.active { border-color: var(--brand); box-shadow: 0 0 0 3px var(--brand-ring); }
                .drp-trigger span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
                .drp-clear {
                    display: inline-flex; align-items: center; justify-content: center; padding: 2px;
                    border-radius: 999px; color: var(--text-muted); flex-shrink: 0; transition: all 0.15s;
                }
                .drp-clear:hover { background: var(--danger-bg); color: var(--danger); }

                .drp-pop {
                    position: absolute; top: 100%; left: 0; margin-top: 8px; z-index: 9999;
                    background: var(--bg-card); border: 1px solid var(--border);
                    border-radius: var(--radius-lg); box-shadow: var(--shadow-lg);
                    padding: 14px; width: 280px; transform: none;
                }
                .drp-pop-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 12px; }
                .drp-nav {
                    display: inline-flex; align-items: center; justify-content: center;
                    width: 28px; height: 28px; border: none; background: transparent; cursor: pointer;
                    border-radius: 999px; color: var(--text-secondary); transition: background 0.15s;
                }
                .drp-nav:hover { background: var(--bg-hover); color: var(--text); }
                .drp-grid { display: grid; grid-template-columns: repeat(7, 1fr); gap: 3px; }
                .drp-dow { text-align: center; font-size: 0.7rem; font-weight: 600; color: var(--text-muted); padding: 4px 0; }
                .drp-day {
                    height: 32px; width: 100%; display: flex; align-items: center; justify-content: center;
                    font-size: 0.82rem; border: none; background: transparent; cursor: pointer;
                    border-radius: var(--radius-sm); color: var(--text); transition: background 0.12s, color 0.12s;
                }
                .drp-day:hover { background: var(--bg-hover); }
                .drp-day.range { background: var(--brand-light); color: var(--brand-dark); border-radius: 0; }
                .drp-day.sel { background: var(--brand); color: #fff; font-weight: 700; border-radius: var(--radius-sm); }
                .drp-foot {
                    margin-top: 12px; padding-top: 11px; border-top: 1px solid var(--border-light);
                    display: flex; justify-content: space-between; align-items: center; font-size: 0.76rem;
                }
                .drp-done {
                    background: none; border: none; cursor: pointer; color: var(--brand);
                    font-weight: 600; font-size: 0.78rem; padding: 2px 6px;
                }
                .drp-done:hover { text-decoration: underline; }
            `}</style>
        </div>
    );
};

export default DateRangePicker;
