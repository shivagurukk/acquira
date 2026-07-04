import React, { useState, useEffect } from 'react';
import { Plus, Edit2, X, Building, Globe } from 'lucide-react';
import api from '../api/axios';
import { useAuth } from '../contexts/AuthContext';
import './TenantManagement.css';

const TenantManagement = () => {
    const { tenantVersion } = useAuth();
    const [tenants, setTenants] = useState([]);
    const [countries, setCountries] = useState([]);
    const [isModalOpen, setIsModalOpen] = useState(false);
    const [currentTenant, setCurrentTenant] = useState({
        bankName: '',
        bankShortCode: '',
        country: '',
        currencyName: '',
        currencySymbol: '',
        baseCurrency: ''
    });

    useEffect(() => {
        fetchTenants();
        fetchCountries();
    }, [tenantVersion]);

    const fetchTenants = async () => {
        try {
            const res = await api.get('/banks');
            setTenants(res.data);
        } catch (error) {
            console.error("Failed to fetch tenants", error);
        }
    };

    const fetchCountries = async () => {
        try {
            const res = await api.get('/admin/countries');
            setCountries(res.data);
        } catch (error) {
            console.error("Failed to fetch countries", error);
        }
    };

    const handleCountryChange = (e) => {
        const countryName = e.target.value;
        const selectedCountry = countries.find(c => c.countryName === countryName);
        if (selectedCountry) {
            setCurrentTenant({
                ...currentTenant,
                country: countryName,
                baseCurrency: selectedCountry.currencyCode,
                currencySymbol: selectedCountry.currencySymbol,
                currencyName: selectedCountry.currencyName
            });
        } else {
            setCurrentTenant({ ...currentTenant, country: countryName });
        }
    };

    const openModal = (tenant = null) => {
        if (tenant) {
            setCurrentTenant(tenant);
        } else {
            setCurrentTenant({ bankName: '', bankShortCode: '', country: '', currencyName: '', currencySymbol: '', baseCurrency: '' });
        }
        setIsModalOpen(true);
    };

    const handleSave = async (e) => {
        e.preventDefault();
        try {
            if (currentTenant.tenantId) {
                await api.put(`/banks/${currentTenant.tenantId}`, currentTenant);
            } else {
                await api.post('/banks', currentTenant);
            }
            fetchTenants();
            setIsModalOpen(false);
        } catch (error) {
            console.error("Failed to save tenant", error);
            alert("Failed to save.");
        }
    };

    return (
        <div className="formal-page-container">
            <div className="formal-header">
                <div>
                    <h1>Tenant Management</h1>
                    <p>Manage Financial Institutions and Jurisdictions</p>
                </div>
                <button className="btn-primary" onClick={() => openModal()}>
                    <Plus size={16} /> Add New Entity
                </button>
            </div>

            <div className="table-container">
                <table className="formal-table">
                    <thead>
                        <tr>
                            <th>Entity Name</th>
                            <th>Short Code</th>
                            <th>Jurisdiction</th>
                            <th>Currency</th>
                            <th style={{ textAlign: 'right' }}>Actions</th>
                        </tr>
                    </thead>
                    <tbody>
                        {tenants.map((tenant) => (
                            <tr key={tenant.tenantId}>
                                <td>
                                    <div className="cell-flex">
                                        <Building size={16} className="text-gray" />
                                        <span className="font-medium">{tenant.bankName}</span>
                                    </div>
                                </td>
                                <td>
                                    <span className="badge-gray">{tenant.bankShortCode}</span>
                                </td>
                                <td>
                                    <div className="cell-flex">
                                        <Globe size={14} className="text-gray" />
                                        {tenant.country || 'Global'}
                                    </div>
                                </td>
                                <td>
                                    {tenant.baseCurrency} <span className="text-muted">({tenant.currencySymbol})</span>
                                </td>
                                <td style={{ textAlign: 'right' }}>
                                    <button className="btn-icon" onClick={() => openModal(tenant)}>
                                        <Edit2 size={16} />
                                    </button>
                                </td>
                            </tr>
                        ))}
                        {tenants.length === 0 && (
                            <tr>
                                <td colSpan="5" className="text-center p-8 text-muted">No entities found.</td>
                            </tr>
                        )}
                    </tbody>
                </table>
            </div>

            {isModalOpen && (
                <div className="modal-overlay">
                    <div className="formal-modal">
                        <div className="modal-header">
                            <h2>{currentTenant.tenantId ? 'Edit Entity' : 'New Entity'}</h2>
                            <button className="btn-close" onClick={() => setIsModalOpen(false)}>
                                <X size={20} />
                            </button>
                        </div>
                        <form onSubmit={handleSave}>
                            <div className="form-group">
                                <label>Entity Name</label>
                                <input
                                    value={currentTenant.bankName}
                                    onChange={e => setCurrentTenant({ ...currentTenant, bankName: e.target.value })}
                                    placeholder="e.g. Acme Bank"
                                    required
                                />
                            </div>
                            <div className="form-group">
                                <label>Short Code</label>
                                <input
                                    value={currentTenant.bankShortCode}
                                    onChange={e => setCurrentTenant({ ...currentTenant, bankShortCode: e.target.value })}
                                    placeholder="e.g. AGB"
                                    required
                                />
                                <small className="text-help">* Matches 'Entity Name' in uploaded files</small>
                            </div>
                            <div className="form-group">
                                <label>Jurisdiction</label>
                                <select
                                    value={currentTenant.country}
                                    onChange={handleCountryChange}
                                >
                                    <option value="">Select Territory</option>
                                    {countries.map(c => (
                                        <option key={c.countryCode} value={c.countryName}>{c.countryName}</option>
                                    ))}
                                </select>
                            </div>
                            <div className="form-row">
                                <div className="form-group">
                                    <label>Currency</label>
                                    <input value={currentTenant.baseCurrency} readOnly placeholder="USD" className="bg-gray" />
                                </div>
                                <div className="form-group">
                                    <label>Symbol</label>
                                    <input value={currentTenant.currencySymbol} readOnly placeholder="$" className="bg-gray" />
                                </div>
                            </div>
                            <div className="modal-footer">
                                <button type="button" className="btn-secondary" onClick={() => setIsModalOpen(false)}>Cancel</button>
                                <button type="submit" className="btn-primary">Save Entity</button>
                            </div>
                        </form>
                    </div>
                </div>
            )}
        </div>
    );
};

export default TenantManagement;
