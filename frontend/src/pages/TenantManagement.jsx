import React, { useState, useEffect, useCallback } from 'react';
import { Plus, Edit2, Building, Globe, Building2 } from 'lucide-react';
import api from '../api/axios';
import { useAuth } from '../contexts/AuthContext';
import { showToast } from '../contexts/ToastContext';
import {
    Page, Card, Button, Badge, DataTable, Modal,
    FormField, FormGrid, Input, Select,
} from '../components/ui';

const emptyTenant = {
    bankName: '',
    bankShortCode: '',
    country: '',
    currencyName: '',
    currencySymbol: '',
    baseCurrency: '',
    inputFormat: 'CMM',
    homeCountryCode: '',
    cardTypeSource: 'FILE',
};

const INPUT_FORMATS = [
    { value: 'CMM', label: 'CMM — amounts in minor units (divided at ingest)' },
    { value: 'AMS', label: 'AMS — amounts already final decimals (no division)' },
];

const CARD_TYPE_SOURCES = [
    { value: 'FILE', label: 'Transaction file — card type/product from uploaded file columns' },
    { value: 'BIN', label: 'BIN mapping — 8-digit BIN table (Super Admin > BIN Management)' },
];

const TenantManagement = ({ embedded = false }) => {
    const { tenantVersion } = useAuth();
    const [tenants, setTenants] = useState([]);
    const [countries, setCountries] = useState([]);
    const [loading, setLoading] = useState(true);
    const [saving, setSaving] = useState(false);
    const [isModalOpen, setIsModalOpen] = useState(false);
    const [currentTenant, setCurrentTenant] = useState(emptyTenant);

    const fetchTenants = useCallback(async () => {
        try {
            const res = await api.get('/banks');
            setTenants(res.data || []);
        } catch (error) {
            console.error('Failed to fetch tenants', error);
            showToast('Could not load entities', 'error');
        }
    }, []);

    const fetchCountries = useCallback(async () => {
        try {
            const res = await api.get('/admin/countries');
            setCountries(res.data || []);
        } catch (error) {
            console.error('Failed to fetch countries', error);
        }
    }, []);

    useEffect(() => {
        setLoading(true);
        Promise.all([fetchTenants(), fetchCountries()]).finally(() => setLoading(false));
    }, [tenantVersion, fetchTenants, fetchCountries]);

    const handleCountryChange = (e) => {
        const countryName = e.target.value;
        const selected = countries.find(c => c.countryName === countryName);
        setCurrentTenant(prev => selected
            ? {
                ...prev,
                country: countryName,
                baseCurrency: selected.currencyCode,
                currencySymbol: selected.currencySymbol,
                currencyName: selected.currencyName,
                // Drives which country's interchange/scheme-fee rate card the
                // fee engine uses for this tenant's transactions.
                homeCountryCode: selected.countryCode,
            }
            : { ...prev, country: countryName });
    };

    const openModal = (tenant = null) => {
        // Older rows may predate input_format / card_type_source — default to legacy.
        setCurrentTenant(tenant ? { inputFormat: 'CMM', cardTypeSource: 'FILE', ...tenant } : emptyTenant);
        setIsModalOpen(true);
    };

    const handleSave = async (e) => {
        e.preventDefault();
        setSaving(true);
        try {
            if (currentTenant.tenantId) {
                await api.put(`/banks/${currentTenant.tenantId}`, currentTenant);
                showToast(`${currentTenant.bankName} updated`, 'success');
            } else {
                await api.post('/banks', currentTenant);
                showToast(`${currentTenant.bankName} created`, 'success');
            }
            fetchTenants();
            setIsModalOpen(false);
        } catch (error) {
            console.error('Failed to save tenant', error);
            showToast(error?.response?.data?.error || 'Could not save the entity', 'error');
        } finally {
            setSaving(false);
        }
    };

    const columns = [
        {
            key: 'bankName',
            header: 'Entity name',
            sortable: true,
            render: t => (
                <span className="ui-row" style={{ gap: 8, flexWrap: 'nowrap' }}>
                    <Building size={15} style={{ color: 'var(--text-muted)', flexShrink: 0 }} />
                    <span style={{ fontWeight: 600 }}>{t.bankName}</span>
                </span>
            ),
        },
        {
            key: 'bankShortCode',
            header: 'Short code',
            sortable: true,
            render: t => <Badge mono>{t.bankShortCode}</Badge>,
        },
        {
            key: 'country',
            header: 'Jurisdiction',
            sortable: true,
            render: t => (
                <span className="ui-row" style={{ gap: 6, flexWrap: 'nowrap' }}>
                    <Globe size={14} style={{ color: 'var(--text-muted)', flexShrink: 0 }} />
                    {t.country || <span className="ui-td--muted">Global</span>}
                </span>
            ),
        },
        {
            key: 'baseCurrency',
            header: 'Currency',
            sortable: true,
            render: t => (
                <>
                    {t.baseCurrency}{' '}
                    <span className="ui-td--muted">({t.currencySymbol})</span>
                </>
            ),
        },
        {
            key: '_actions',
            header: '',
            align: 'right',
            width: 60,
            render: t => (
                <Button
                    variant="ghost"
                    size="sm"
                    iconOnly
                    icon={Edit2}
                    onClick={() => openModal(t)}
                    aria-label={`Edit ${t.bankName}`}
                />
            ),
        },
    ];

    return (
        <Page
            flush={embedded}
            title="Tenant management"
            subtitle="Financial institutions and the jurisdictions they operate in."
            icon={Building2}
            actions={
                <Button variant="primary" icon={Plus} onClick={() => openModal()}>
                    Add entity
                </Button>
            }
        >
            <Card>
                <DataTable
                    columns={columns}
                    rows={tenants}
                    rowKey={t => t.tenantId}
                    loading={loading}
                    defaultSort={{ key: 'bankName', dir: 'asc' }}
                    emptyVariant="data"
                />
            </Card>

            <Modal
                as="form"
                onSubmit={handleSave}
                open={isModalOpen}
                onClose={() => setIsModalOpen(false)}
                title={currentTenant.tenantId ? 'Edit entity' : 'New entity'}
                subtitle="Currency is derived from the selected jurisdiction."
                footer={
                    <>
                        <Button type="button" onClick={() => setIsModalOpen(false)}>Cancel</Button>
                        <Button type="submit" variant="primary" loading={saving}>Save entity</Button>
                    </>
                }
            >
                <div className="ui-stack ui-stack--sm">
                    <FormField label="Entity name" required>
                        <Input
                            value={currentTenant.bankName}
                            onChange={e => setCurrentTenant({ ...currentTenant, bankName: e.target.value })}
                            placeholder="e.g. Acme Bank"
                            required
                        />
                    </FormField>

                    <FormField
                        label="Short code"
                        required
                        hint="Must match the entity name used in uploaded files."
                    >
                        <Input
                            value={currentTenant.bankShortCode}
                            onChange={e => setCurrentTenant({ ...currentTenant, bankShortCode: e.target.value })}
                            placeholder="e.g. AGB"
                            required
                        />
                    </FormField>

                    <FormField label="Jurisdiction">
                        <Select
                            value={currentTenant.country}
                            onChange={handleCountryChange}
                            placeholder="Select territory"
                            options={countries.map(c => ({ value: c.countryName, label: c.countryName }))}
                        />
                    </FormField>

                    <FormGrid cols={2}>
                        <FormField label="Currency">
                            <Input value={currentTenant.baseCurrency} readOnly placeholder="USD" />
                        </FormField>
                        <FormField label="Symbol">
                            <Input value={currentTenant.currencySymbol} readOnly placeholder="$" />
                        </FormField>
                    </FormGrid>

                    <FormField
                        label="Feed amount format"
                        required
                        hint="CMM: feed sends minor units (e.g. fils/cents) and amounts are divided at ingest. AMS: feed sends final decimal amounts — no division. Applies to file uploads and scheduled pulls."
                    >
                        <Select
                            value={currentTenant.inputFormat || 'CMM'}
                            onChange={e => setCurrentTenant({ ...currentTenant, inputFormat: e.target.value })}
                            options={INPUT_FORMATS}
                        />
                    </FormField>

                    <FormField
                        label="Card product/type source"
                        hint="Where the card type and product for this tenant's transactions come from. Configuration only for now — ingestion behavior is unchanged until the enrichment phase is enabled."
                    >
                        <Select
                            value={currentTenant.cardTypeSource || 'FILE'}
                            onChange={e => setCurrentTenant({ ...currentTenant, cardTypeSource: e.target.value })}
                            options={CARD_TYPE_SOURCES}
                        />
                    </FormField>
                </div>
            </Modal>
        </Page>
    );
};

export default TenantManagement;
