import React, { useEffect, useState } from 'react';
import api from '../api/axios';

/* Merchant-name suggestions for a search <input>: renders a native <datalist>
   the input points at via list={id}. Fetches 200ms after the last keystroke,
   from two characters on; a stale request is aborted by the next keystroke. */
const MerchantSuggest = ({ id, query }) => {
    const [names, setNames] = useState([]);

    useEffect(() => {
        const q = (query || '').trim();
        if (q.length < 2) { setNames([]); return undefined; }
        const ctrl = new AbortController();
        const t = setTimeout(() => {
            api.get('/business/merchant-suggest', { params: { q }, signal: ctrl.signal })
                .then(res => setNames(Array.isArray(res.data) ? res.data : []))
                .catch(() => { /* suggestions are optional — the search still works */ });
        }, 200);
        return () => { clearTimeout(t); ctrl.abort(); };
    }, [query]);

    return (
        <datalist id={id}>
            {names.map(n => <option key={n} value={n} />)}
        </datalist>
    );
};

export default MerchantSuggest;
