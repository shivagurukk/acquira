import React, { useState, useEffect } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import { Plus, Building2, Globe, Banknote, X } from 'lucide-react';

const BankManagement = () => {
    const [banks, setBanks] = useState([]);
    const [isModalOpen, setIsModalOpen] = useState(false);
    const [currentBank, setCurrentBank] = useState({ name: '', country: '', currency: '', code: '' });
    const [loading, setLoading] = useState(false);

    useEffect(() => {
        fetchBanks();
    }, []);

    const fetchBanks = async () => {
        try {
            const res = await fetch('/api/banks');
            if (res.ok) {
                const data = await res.json();
                setBanks(data);
            }
        } catch (err) {
            console.error(err);
        }
    };

    const handleSave = async (e) => {
        e.preventDefault();
        setLoading(true);
        try {
            const res = await fetch('/api/banks', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(currentBank)
            });
            if (res.ok) {
                fetchBanks();
                setIsModalOpen(false);
                setCurrentBank({ name: '', country: '', currency: '', code: '' });
            }
        } catch (err) {
            alert('Failed to create bank');
        } finally {
            setLoading(false);
        }
    };

    return (
        <div style={{ padding: '40px', color: '#1e293b' }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: '30px' }}>
                <h1 style={{ fontSize: '24px', fontWeight: 'bold' }}>Bank Management</h1>
                <button
                    onClick={() => setIsModalOpen(true)}
                    style={{ background: '#0f172a', color: 'white', padding: '10px 20px', borderRadius: '8px', display: 'flex', gap: '8px', alignItems: 'center' }}
                >
                    <Plus size={18} /> Add New Bank
                </button>
            </div>

            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(300px, 1fr))', gap: '20px' }}>
                {banks.map(bank => (
                    <div key={bank.id} style={{ background: 'white', padding: '24px', borderRadius: '12px', border: '1px solid #e2e8f0', boxShadow: '0 4px 6px -1px rgba(0,0,0,0.05)' }}>
                        <div style={{ display: 'flex', alignItems: 'center', gap: '12px', marginBottom: '16px' }}>
                            <div style={{ background: '#eff6ff', padding: '10px', borderRadius: '8px', color: '#3b82f6' }}>
                                <Building2 size={24} />
                            </div>
                            <div>
                                <h3 style={{ fontWeight: 'bold', fontSize: '18px' }}>{bank.name}</h3>
                                <span style={{ fontSize: '12px', color: '#64748b', background: '#f1f5f9', padding: '2px 8px', borderRadius: '4px' }}>{bank.code}</span>
                            </div>
                        </div>
                        <div style={{ display: 'flex', gap: '20px', fontSize: '14px', color: '#64748b' }}>
                            <span style={{ display: 'flex', alignItems: 'center', gap: '6px' }}><Globe size={16} /> {bank.country}</span>
                            <span style={{ display: 'flex', alignItems: 'center', gap: '6px' }}><Banknote size={16} /> {bank.currency}</span>
                        </div>
                    </div>
                ))}
            </div>

            <AnimatePresence>
                {isModalOpen && (
                    <div style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.5)', display: 'flex', justifyContent: 'center', alignItems: 'center', zIndex: 50 }}>
                        <motion.div
                            initial={{ opacity: 0, scale: 0.95 }}
                            animate={{ opacity: 1, scale: 1 }}
                            exit={{ opacity: 0, scale: 0.95 }}
                            style={{ background: 'white', padding: '30px', borderRadius: '16px', width: '100%', maxWidth: '500px' }}
                        >
                            <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: '24px' }}>
                                <h2 style={{ fontSize: '20px', fontWeight: 'bold' }}>Create New Bank</h2>
                                <button onClick={() => setIsModalOpen(false)}><X size={24} /></button>
                            </div>
                            <form onSubmit={handleSave} style={{ display: 'flex', flexDirection: 'column', gap: '16px' }}>
                                <div>
                                    <label style={{ display: 'block', marginBottom: '8px', fontWeight: '500' }}>Bank Name</label>
                                    <input
                                        style={{ width: '100%', padding: '10px', borderRadius: '8px', border: '1px solid #cbd5e1' }}
                                        value={currentBank.name}
                                        onChange={e => setCurrentBank({ ...currentBank, name: e.target.value })}
                                        required
                                    />
                                </div>
                                <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '16px' }}>
                                    <div>
                                        <label style={{ display: 'block', marginBottom: '8px', fontWeight: '500' }}>Country</label>
                                        <input
                                            style={{ width: '100%', padding: '10px', borderRadius: '8px', border: '1px solid #cbd5e1' }}
                                            value={currentBank.country}
                                            onChange={e => setCurrentBank({ ...currentBank, country: e.target.value })}
                                            required
                                        />
                                    </div>
                                    <div>
                                        <label style={{ display: 'block', marginBottom: '8px', fontWeight: '500' }}>Currency</label>
                                        <input
                                            style={{ width: '100%', padding: '10px', borderRadius: '8px', border: '1px solid #cbd5e1' }}
                                            value={currentBank.currency}
                                            onChange={e => setCurrentBank({ ...currentBank, currency: e.target.value })}
                                            required
                                        />
                                    </div>
                                </div>
                                <div>
                                    <label style={{ display: 'block', marginBottom: '8px', fontWeight: '500' }}>Bank Code (Optional)</label>
                                    <input
                                        style={{ width: '100%', padding: '10px', borderRadius: '8px', border: '1px solid #cbd5e1' }}
                                        value={currentBank.code}
                                        onChange={e => setCurrentBank({ ...currentBank, code: e.target.value })}
                                        placeholder="Auto-generated if empty"
                                    />
                                </div>
                                <button
                                    type="submit"
                                    disabled={loading}
                                    style={{ background: '#0f172a', color: 'white', padding: '12px', borderRadius: '8px', marginTop: '10px', fontWeight: '600' }}
                                >
                                    {loading ? 'Creating...' : 'Create Bank'}
                                </button>
                            </form>
                        </motion.div>
                    </div>
                )}
            </AnimatePresence>
        </div>
    );
};

export default BankManagement;
