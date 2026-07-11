import React, { useState, useEffect } from 'react';
import {
    Drawer, Box, Typography, IconButton, Stack, TextField, Button,
    InputAdornment, Autocomplete, ToggleButton, ToggleButtonGroup,
    Chip, Divider
} from '@mui/material';
import {
    Filter, X, RefreshCw, Search, Briefcase, Layers, Monitor, Hash
} from 'lucide-react';
import { cachedGet } from '../api/apiCache';

const DEFAULT_OPTIONS = {
    partners: [], mccs: [], industries: ['Retail', 'F&B', 'Services', 'Travel', 'Education', 'Healthcare'],
    rms: [], teamLeaders: [], sectors: ['SME', 'Corporate', 'Government'],
    schemes: ['VISA', 'MASTERCARD', 'MADA', 'AMEX'],
    cardTypes: ['CREDIT', 'DEBIT', 'PREPAID', 'COMMERCIAL'],
    destinations: ['DOMESTIC', 'INTERNATIONAL'],
    channels: ['POS', 'ECOM', 'MOTO'],
    mids: [], sids: [],
};

const DarkTextField = (props) => (
    <TextField fullWidth variant="outlined" size="small"
        sx={{
            '& .MuiOutlinedInput-root': {
                color: '#F8FAFC', backgroundColor: '#1F2937',
                '& fieldset': { borderColor: '#374151' },
                '&:hover fieldset': { borderColor: '#64748B' },
                '&.Mui-focused fieldset': { borderColor: '#3B82F6' },
            },
            '& .MuiInputLabel-root': { color: '#94A3B8' },
            '& .MuiInputLabel-root.Mui-focused': { color: '#3B82F6' },
            ...props.sx
        }}
        {...props}
    />
);

const DarkAutocomplete = ({ label, options, value, onChange, placeholder, freeSolo = false, getOptionLabel }) => (
    <Autocomplete
        multiple freeSolo={freeSolo}
        options={options || []} value={value || []}
        {...(getOptionLabel ? { getOptionLabel } : {})}
        onChange={(e, newVal) => onChange(newVal)}
        renderInput={(params) => <DarkTextField {...params} label={label} placeholder={value?.length ? '' : placeholder} />}
        renderTags={(value, getTagProps) =>
            value.map((option, index) => (
                <Chip {...getTagProps({ index })} key={option} label={getOptionLabel ? getOptionLabel(option) : option} size="small"
                    sx={{ bgcolor: '#3B82F6', color: 'white', fontWeight: 600, '& .MuiChip-deleteIcon': { color: 'white !important', opacity: 0.7 } }}
                />
            ))
        }
        sx={{
            '& .MuiAutocomplete-popupIndicator': { color: '#94A3B8' },
            '& .MuiAutocomplete-clearIndicator': { color: '#94A3B8' },
            '& .MuiAutocomplete-tag': { margin: 0.5 }
        }}
        PaperComponent={({ children }) => (
            <Box sx={{ bgcolor: '#1F2937', color: 'white', '& .MuiAutocomplete-option': { '&:hover': { bgcolor: '#374151' } }, '& .MuiAutocomplete-listbox': { bgcolor: '#1F2937' } }}>
                {children}
            </Box>
        )}
    />
);


const BusinessFilters = ({ filters, onChange, onApply, isOpen, onClose, hideDestination = false }) => {
    const [dateType, setDateType] = useState('TRANSACTION');
    const [options, setOptions] = useState(DEFAULT_OPTIONS);
    // MCC code -> sector/category label (from ref_mcc_category via filter-options).
    // Index-aligned lists (mccs / mccCategories) are zipped into a lookup so the
    // MCC dropdown reads "5712 — Furniture" while the applied value stays the code.
    const [mccLabels, setMccLabels] = useState({});
    const mccLabel = (code) => (mccLabels[code] && mccLabels[code] !== 'MIS') ? `${code} — ${mccLabels[code]}` : String(code);

    useEffect(() => {
        const fetchOptions = async () => {
            try {
                // Filter option lists are near-static per tenant/session — served
                // from the shared client cache so re-opening the drawer on any
                // page doesn't re-hit the backend. Cache is tenant-scoped + TTL'd.
                const res = await cachedGet('/business/filter-options');
                const data = res.data;
                setOptions(prev => ({
                    ...prev, ...data,
                    schemes: Array.from(new Set([...DEFAULT_OPTIONS.schemes, ...(data.schemes || [])])),
                    cardTypes: Array.from(new Set([...DEFAULT_OPTIONS.cardTypes, ...(data.cardTypes || [])])),
                    destinations: Array.from(new Set([...DEFAULT_OPTIONS.destinations, ...(data.destinations || [])])),
                    channels: Array.from(new Set([...DEFAULT_OPTIONS.channels, ...(data.channels || [])])),
                    teamLeaders: Array.from(new Set([...DEFAULT_OPTIONS.teamLeaders, ...(data.teamLeaders || [])])),
                    rms: Array.from(new Set([...DEFAULT_OPTIONS.rms, ...(data.rms || [])])),
                    mccs: (data.mccs || []).map(m => String(m)),
                    mids: (data.mids || []).map(m => String(m)),
                    sids: (data.sids || []).map(m => String(m)),
                    industries: (data.industries && data.industries.length)
                        ? data.industries : DEFAULT_OPTIONS.industries,
                }));
                if (data.mccCategories && data.mccs && data.mccCategories.length === data.mccs.length) {
                    const map = {};
                    data.mccs.forEach((code, i) => { map[String(code)] = data.mccCategories[i]; });
                    setMccLabels(map);
                }
            } catch (error) { console.error("Failed to fetch filter options", error); }
        };
        if (isOpen) fetchOptions();
    }, [isOpen]);

    const update = (key, val) => onChange({ ...filters, [key]: val });

    const handleApply = () => { onApply(); onClose(); };

    const handleReset = () => {
        onChange({
            startDate: '', endDate: '', openDateStart: '', openDateEnd: '',
            partnerList: [], mccList: [], industryList: [], rmList: [], teamLeaderList: [],
            sectorList: [], destinationList: [], schemeList: [], cardTypeList: [], channelList: [],
            merchantName: '', midList: [], sidList: [],
        });
    };

    return (
        <Drawer anchor="right" open={isOpen} onClose={onClose}
            PaperProps={{ sx: { width: 450, bgcolor: '#111827', color: '#F8FAFC', borderLeft: '1px solid #1F2937' } }}
        >
            {/* Header */}
            <Box sx={{ p: 3, borderBottom: '1px solid #1F2937', display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                <Stack direction="row" spacing={1.5} alignItems="center">
                    <Box sx={{ width: 36, height: 36, borderRadius: '10px', display: 'flex', alignItems: 'center', justifyContent: 'center', background: 'linear-gradient(135deg, #3B82F6 0%, #6366f1 100%)' }}>
                        <Filter size={18} color="white" />
                    </Box>
                    <Box>
                        <Typography variant="h6" fontWeight="700">Refine Analytics</Typography>
                        <Typography variant="caption" color="#94A3B8">Apply filters to narrow results</Typography>
                    </Box>
                </Stack>
                <IconButton onClick={onClose} sx={{ color: '#94A3B8', '&:hover': { color: 'white', bgcolor: '#374151' } }}>
                    <X size={20} />
                </IconButton>
            </Box>

            {/* Scrollable Body */}
            <Box sx={{ p: 3, flex: 1, overflowY: 'auto' }}>

                {/* ★ MID & SID — Top Priority Filters ★ */}
                <Box mb={4}>
                    <Stack direction="row" spacing={1} alignItems="center" mb={1.5}>
                        <Hash size={14} color="#3B82F6" />
                        <Typography variant="overline" color="#3B82F6" fontWeight="700" letterSpacing={1}>Merchant & Store ID</Typography>
                    </Stack>
                    <Stack spacing={2}>
                        <DarkAutocomplete
                            label="MID (Merchant ID)" freeSolo
                            options={options.mids}
                            value={filters.midList || []}
                            onChange={(v) => update('midList', v)}
                            placeholder="Search or type MIDs..."
                        />
                        <DarkAutocomplete
                            label="SID (Store ID)" freeSolo
                            options={options.sids}
                            value={filters.sidList || []}
                            onChange={(v) => update('sidList', v)}
                            placeholder="Search or type SIDs..."
                        />
                    </Stack>
                </Box>

                <Divider sx={{ borderColor: '#1F2937', mb: 3 }} />

                {/* Merchant Search */}
                <Box mb={4}>
                    <Typography variant="overline" color="text.secondary" fontWeight="700" display="block" mb={1} letterSpacing={1}>Merchant Search</Typography>
                    <DarkTextField placeholder="Search by Merchant Name..."
                        value={filters.merchantName || ''}
                        onChange={e => update('merchantName', e.target.value)}
                        InputProps={{ startAdornment: <InputAdornment position="start"><Search size={18} color="#94A3B8" /></InputAdornment> }}
                    />
                </Box>

                {/* Date Range */}
                <Box mb={4}>
                    <Typography variant="overline" color="text.secondary" fontWeight="700" display="block" mb={1} letterSpacing={1}>Date Range</Typography>
                    <ToggleButtonGroup value={dateType} exclusive onChange={(e, val) => val && setDateType(val)} fullWidth size="small"
                        sx={{ mb: 2, '& .MuiToggleButton-root': { color: '#94A3B8', borderColor: '#374151', '&.Mui-selected': { color: '#3B82F6', bgcolor: 'rgba(59, 130, 246, 0.1)' } } }}
                    >
                        <ToggleButton value="TRANSACTION">Transaction Date</ToggleButton>
                        <ToggleButton value="OPEN">Open Date</ToggleButton>
                    </ToggleButtonGroup>
                    <Stack direction="row" spacing={2}>
                        <DarkTextField type="date"
                            value={dateType === 'TRANSACTION' ? filters.startDate : filters.openDateStart}
                            onChange={(e) => update(dateType === 'TRANSACTION' ? 'startDate' : 'openDateStart', e.target.value)}
                        />
                        <DarkTextField type="date"
                            value={dateType === 'TRANSACTION' ? filters.endDate : filters.openDateEnd}
                            onChange={(e) => update(dateType === 'TRANSACTION' ? 'endDate' : 'openDateEnd', e.target.value)}
                        />
                    </Stack>
                </Box>

                {/* Organization Entity */}
                <Box mb={4}>
                    <Stack direction="row" spacing={1} alignItems="center" mb={1.5}>
                        <Briefcase size={14} color="#94A3B8" />
                        <Typography variant="overline" color="text.secondary" fontWeight="700" letterSpacing={1}>Organization Entity</Typography>
                    </Stack>
                    <Stack spacing={2}>
                        <DarkAutocomplete label="Partner" options={options.partners} value={filters.partnerList} onChange={(v) => update('partnerList', v)} placeholder="All Partners" />
                        <DarkAutocomplete label="Relationship Manager" options={options.rms} value={filters.rmList} onChange={(v) => update('rmList', v)} placeholder="All RMs" />
                        <DarkAutocomplete label="Team Leader" options={options.teamLeaders} value={filters.teamLeaderList} onChange={(v) => update('teamLeaderList', v)} placeholder="All Leads" />
                    </Stack>
                </Box>

                {/* Classification */}
                <Box mb={4}>
                    <Stack direction="row" spacing={1} alignItems="center" mb={1.5}>
                        <Layers size={14} color="#94A3B8" />
                        <Typography variant="overline" color="text.secondary" fontWeight="700" letterSpacing={1}>Classification</Typography>
                    </Stack>
                    <Stack spacing={2}>
                        <DarkAutocomplete label="MCC" options={options.mccs} value={filters.mccList} onChange={(v) => update('mccList', v)} placeholder="All MCCs" getOptionLabel={mccLabel} />
                        <DarkAutocomplete label="Industry" options={options.industries} value={filters.industryList} onChange={(v) => update('industryList', v)} placeholder="All Industries" />
                    </Stack>
                </Box>

                {/* Transaction Tech */}
                <Box mb={2}>
                    <Stack direction="row" spacing={1} alignItems="center" mb={1.5}>
                        <Monitor size={14} color="#94A3B8" />
                        <Typography variant="overline" color="text.secondary" fontWeight="700" letterSpacing={1}>Transaction Tech</Typography>
                    </Stack>
                    <Box display="grid" gridTemplateColumns="1fr 1fr" gap={2}>
                        <DarkAutocomplete label="Channel" options={options.channels} value={filters.channelList} onChange={(v) => update('channelList', v)} placeholder="All" />
                        {!hideDestination && (
                            <DarkAutocomplete label="Destination" options={options.destinations} value={filters.destinationList} onChange={(v) => update('destinationList', v)} placeholder="All" />
                        )}
                        <DarkAutocomplete label="Scheme" options={options.schemes} value={filters.schemeList} onChange={(v) => update('schemeList', v)} placeholder="All" />
                        <DarkAutocomplete label="Card Type" options={options.cardTypes} value={filters.cardTypeList} onChange={(v) => update('cardTypeList', v)} placeholder="All" />
                    </Box>
                    {hideDestination && (
                        <Typography variant="caption" sx={{ display: 'block', mt: 1, color: '#64748B', fontStyle: 'italic' }}>
                            Destination is controlled by the Domestic / International switcher on this page.
                        </Typography>
                    )}
                </Box>
            </Box>

            {/* Footer */}
            <Box sx={{ p: 3, borderTop: '1px solid #1F2937', bgcolor: '#0F172A', display: 'flex', gap: 2 }}>
                <Button variant="outlined" fullWidth onClick={handleReset}
                    sx={{ color: '#94A3B8', borderColor: '#374151', '&:hover': { borderColor: 'white', color: 'white' } }}>
                    Reset
                </Button>
                <Button variant="contained" fullWidth onClick={handleApply} startIcon={<RefreshCw size={18} />}
                    sx={{ bgcolor: '#3B82F6', '&:hover': { bgcolor: '#2563EB' }, fontWeight: 700 }}>
                    Apply Filters
                </Button>
            </Box>
        </Drawer>
    );
};

export default BusinessFilters;
