-- 01_ddl.sql - schema only.
-- Generated 2026-11-12 11:16 by scripts/freshdb/build_fresh_db_kit.py (repository commit 49b891c + uncommitted SQL changes).
-- Part of the fresh-database kit: run 00_create_fresh_db.sql, not this file on its own.

--
-- PostgreSQL database dump
--


-- Dumped from database version 18.1
-- Dumped by pg_dump version 18.1

SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

--
-- Name: get_current_tenant(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.get_current_tenant() RETURNS bigint
    LANGUAGE sql STABLE SECURITY DEFINER
    AS $$
    SELECT CAST(NULLIF(current_setting('app.current_tenant', true), '') AS BIGINT);
$$;


SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: access_request; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.access_request (
    request_id bigint NOT NULL,
    email character varying(255) NOT NULL,
    display_name character varying(150),
    sso_provider character varying(20),
    sso_id character varying(255),
    requested_tenant_id integer,
    message text,
    status character varying(20) DEFAULT 'PENDING'::character varying,
    reviewed_by bigint,
    reviewed_at timestamp without time zone,
    review_notes text,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP
);


--
-- Name: access_request_request_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.access_request_request_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: access_request_request_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.access_request_request_id_seq OWNED BY public.access_request.request_id;


--
-- Name: ai_chat_history; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ai_chat_history (
    chat_id bigint NOT NULL,
    tenant_id bigint NOT NULL,
    user_id bigint NOT NULL,
    question text NOT NULL,
    generated_sql text,
    summary text,
    row_count integer,
    duration_ms bigint,
    is_error boolean DEFAULT false,
    error_msg text,
    created_at timestamp without time zone DEFAULT now()
);


--
-- Name: ai_chat_history_chat_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.ai_chat_history_chat_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ai_chat_history_chat_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.ai_chat_history_chat_id_seq OWNED BY public.ai_chat_history.chat_id;


--
-- Name: alert_history; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.alert_history (
    alert_id bigint NOT NULL,
    rule_id bigint,
    tenant_id integer,
    rule_name character varying(200),
    severity character varying(20),
    merchant_name character varying(200),
    merchant_id bigint,
    message text,
    metric_value numeric(18,4),
    acknowledged boolean DEFAULT false,
    acknowledged_by character varying(100),
    acknowledged_at timestamp without time zone,
    triggered_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP
);

ALTER TABLE ONLY public.alert_history FORCE ROW LEVEL SECURITY;


--
-- Name: alert_history_alert_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.alert_history_alert_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: alert_history_alert_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.alert_history_alert_id_seq OWNED BY public.alert_history.alert_id;


--
-- Name: alert_rule; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.alert_rule (
    rule_id bigint NOT NULL,
    tenant_id integer,
    name character varying(200) NOT NULL,
    description text,
    metric character varying(100) NOT NULL,
    operator character varying(5) DEFAULT '>'::character varying NOT NULL,
    threshold numeric(18,4) DEFAULT 0 NOT NULL,
    severity character varying(20) DEFAULT 'WARNING'::character varying NOT NULL,
    recipients text,
    is_active boolean DEFAULT true NOT NULL,
    check_frequency character varying(20) DEFAULT 'DAILY'::character varying,
    scope character varying(50) DEFAULT 'ALL_MERCHANTS'::character varying,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP
);

ALTER TABLE ONLY public.alert_rule FORCE ROW LEVEL SECURITY;


--
-- Name: alert_rule_rule_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.alert_rule_rule_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: alert_rule_rule_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.alert_rule_rule_id_seq OWNED BY public.alert_rule.rule_id;


--
-- Name: api_idempotency; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.api_idempotency (
    tenant_id bigint NOT NULL,
    idem_key character varying(120) NOT NULL,
    method character varying(10) NOT NULL,
    endpoint character varying(300) NOT NULL,
    response_status integer,
    response_body text,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: api_key; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.api_key (
    key_id bigint NOT NULL,
    tenant_id integer,
    name character varying(200) NOT NULL,
    key_hash character varying(255) NOT NULL,
    key_prefix character varying(20) NOT NULL,
    permissions text,
    is_active boolean DEFAULT true NOT NULL,
    created_by character varying(100),
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    last_used timestamp without time zone,
    request_count bigint DEFAULT 0,
    revoked_at timestamp without time zone,
    revoked_by character varying(100),
    expires_at timestamp without time zone,
    rate_limit_per_minute integer DEFAULT 120,
    allowed_ips text,
    last_used_ip character varying(64),
    updated_at timestamp without time zone,
    quota_per_day integer,
    rotated_at timestamp without time zone,
    rotated_by character varying(120)
);

ALTER TABLE ONLY public.api_key FORCE ROW LEVEL SECURITY;


--
-- Name: api_key_key_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.api_key_key_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: api_key_key_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.api_key_key_id_seq OWNED BY public.api_key.key_id;


--
-- Name: api_request_log; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.api_request_log (
    log_id bigint NOT NULL,
    tenant_id integer,
    key_id bigint,
    method character varying(8),
    endpoint character varying(300),
    status integer,
    client_ip character varying(64),
    latency_ms integer,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP
);


--
-- Name: api_request_log_log_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.api_request_log_log_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: api_request_log_log_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.api_request_log_log_id_seq OWNED BY public.api_request_log.log_id;


--
-- Name: audit_log; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.audit_log (
    log_id bigint NOT NULL,
    tenant_id integer,
    user_id bigint,
    username character varying(50),
    action_type character varying(50),
    details text,
    ip_address character varying(45),
    event_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    http_method character varying(10),
    endpoint character varying(255),
    status_code integer,
    user_agent character varying(500),
    category character varying(50),
    entity_type character varying(50),
    entity_id character varying(100),
    duration_ms bigint
);


--
-- Name: audit_log_log_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.audit_log_log_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: audit_log_log_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.audit_log_log_id_seq OWNED BY public.audit_log.log_id;


--
-- Name: bank_budget_target; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.bank_budget_target (
    budget_id integer NOT NULL,
    tenant_id integer NOT NULL,
    month_key integer,
    metric_type character varying(50),
    target_value numeric(21,4),
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP
);


--
-- Name: bank_budget_target_budget_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.bank_budget_target_budget_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: bank_budget_target_budget_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.bank_budget_target_budget_id_seq OWNED BY public.bank_budget_target.budget_id;


--
-- Name: batch_job_execution; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.batch_job_execution (
    job_execution_id bigint NOT NULL,
    version bigint,
    job_instance_id bigint NOT NULL,
    create_time timestamp without time zone NOT NULL,
    start_time timestamp without time zone,
    end_time timestamp without time zone,
    status character varying(10),
    exit_code character varying(2500),
    exit_message character varying(2500),
    last_updated timestamp without time zone
);


--
-- Name: batch_job_execution_context; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.batch_job_execution_context (
    job_execution_id bigint NOT NULL,
    short_context character varying(2500) NOT NULL,
    serialized_context text
);


--
-- Name: batch_job_execution_params; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.batch_job_execution_params (
    job_execution_id bigint NOT NULL,
    parameter_name character varying(100) NOT NULL,
    parameter_type character varying(100) NOT NULL,
    parameter_value character varying(2500),
    identifying character(1) NOT NULL
);


--
-- Name: batch_job_execution_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.batch_job_execution_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: batch_job_instance; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.batch_job_instance (
    job_instance_id bigint NOT NULL,
    version bigint,
    job_name character varying(100) NOT NULL,
    job_key character varying(32) NOT NULL
);


--
-- Name: batch_job_instance_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.batch_job_instance_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: batch_job_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.batch_job_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: batch_run_log; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.batch_run_log (
    run_id bigint NOT NULL,
    tenant_id bigint,
    job_name character varying(100),
    start_time timestamp without time zone,
    end_time timestamp without time zone,
    status character varying(20),
    records_processed integer DEFAULT 0,
    records_failed integer DEFAULT 0,
    error_message text
);


--
-- Name: batch_run_log_run_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.batch_run_log_run_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: batch_run_log_run_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.batch_run_log_run_id_seq OWNED BY public.batch_run_log.run_id;


--
-- Name: batch_step_execution; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.batch_step_execution (
    step_execution_id bigint NOT NULL,
    version bigint NOT NULL,
    step_name character varying(100) NOT NULL,
    job_execution_id bigint NOT NULL,
    create_time timestamp without time zone NOT NULL,
    start_time timestamp without time zone,
    end_time timestamp without time zone,
    status character varying(10),
    commit_count bigint,
    read_count bigint,
    filter_count bigint,
    write_count bigint,
    read_skip_count bigint,
    write_skip_count bigint,
    process_skip_count bigint,
    rollback_count bigint,
    exit_code character varying(2500),
    exit_message character varying(2500),
    last_updated timestamp without time zone
);


--
-- Name: batch_step_execution_context; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.batch_step_execution_context (
    step_execution_id bigint NOT NULL,
    short_context character varying(2500) NOT NULL,
    serialized_context text
);


--
-- Name: batch_step_execution_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.batch_step_execution_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: dashboard_config; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.dashboard_config (
    config_id integer NOT NULL,
    tenant_id integer,
    kpi_key character varying(50) NOT NULL,
    display_label character varying(100),
    is_visible boolean DEFAULT true,
    display_order integer
);


--
-- Name: dashboard_config_config_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.dashboard_config_config_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: dashboard_config_config_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.dashboard_config_config_id_seq OWNED BY public.dashboard_config.config_id;


--
-- Name: data_source_config; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.data_source_config (
    id bigint NOT NULL,
    name character varying(255) NOT NULL,
    db_type character varying(50) NOT NULL,
    host character varying(255) NOT NULL,
    port integer NOT NULL,
    db_name character varying(255) NOT NULL,
    username character varying(255) NOT NULL,
    encrypted_password character varying(255) NOT NULL,
    is_active boolean DEFAULT true,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP
);


--
-- Name: data_source_config_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.data_source_config_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: data_source_config_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.data_source_config_id_seq OWNED BY public.data_source_config.id;


--
-- Name: db_maintenance_config; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.db_maintenance_config (
    id smallint DEFAULT 1 NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    window_start_hour integer DEFAULT 2 NOT NULL,
    window_end_hour integer DEFAULT 5 NOT NULL,
    tables_csv text,
    last_run_date date,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT db_maintenance_config_singleton CHECK ((id = 1))
);


--
-- Name: db_maintenance_run; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.db_maintenance_run (
    id bigint NOT NULL,
    started_at timestamp with time zone DEFAULT now() NOT NULL,
    finished_at timestamp with time zone,
    status character varying(16) NOT NULL,
    trigger character varying(16) DEFAULT 'SCHEDULED'::character varying NOT NULL,
    tables_done integer DEFAULT 0,
    detail text
);


--
-- Name: db_maintenance_run_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.db_maintenance_run_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: db_maintenance_run_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.db_maintenance_run_id_seq OWNED BY public.db_maintenance_run.id;


--
-- Name: destination_token_map; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.destination_token_map (
    id bigint NOT NULL,
    tenant_id integer,
    country_code character varying(2) NOT NULL,
    feed_code character varying(30),
    raw_token character varying(40) NOT NULL,
    dest character varying(20) NOT NULL,
    note character varying(120)
);


--
-- Name: destination_token_map_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.destination_token_map_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: destination_token_map_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.destination_token_map_id_seq OWNED BY public.destination_token_map.id;


--
-- Name: digest_config; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.digest_config (
    tenant_id bigint NOT NULL,
    enabled boolean DEFAULT false NOT NULL,
    recipients text,
    quiet_minutes integer DEFAULT 15 NOT NULL,
    require_trx boolean DEFAULT true NOT NULL,
    require_dcc boolean DEFAULT false NOT NULL,
    require_rental boolean DEFAULT false NOT NULL,
    backfill_window_days integer DEFAULT 3 NOT NULL,
    updated_by character varying(128),
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    send_not_before time without time zone,
    require_merchant boolean DEFAULT false NOT NULL,
    subject_figures boolean DEFAULT false NOT NULL,
    allowed_domains text,
    restate_mode character varying(16) DEFAULT 'ALERT'::character varying NOT NULL,
    restate_threshold_pct numeric(6,2) DEFAULT 1.00 NOT NULL,
    allow_today boolean DEFAULT true NOT NULL
);


--
-- Name: digest_dispatch; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.digest_dispatch (
    id bigint NOT NULL,
    tenant_id bigint NOT NULL,
    business_date date NOT NULL,
    status character varying(16) DEFAULT 'PENDING'::character varying NOT NULL,
    waiting_on character varying(64),
    attempts integer DEFAULT 0 NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    sent_at timestamp without time zone,
    recipients_sent text,
    error_message text,
    sent_subject text,
    sent_html text,
    recipients_failed text,
    feeds_included character varying(64),
    sent_rows_fact bigint,
    sent_gross_amount numeric(21,4),
    restate_count integer DEFAULT 0 NOT NULL,
    restated_at timestamp without time zone,
    claimed_at timestamp without time zone
);


--
-- Name: digest_dispatch_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.digest_dispatch_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: digest_dispatch_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.digest_dispatch_id_seq OWNED BY public.digest_dispatch.id;


--
-- Name: dim_aggregator; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.dim_aggregator (
    aggregator_id integer NOT NULL,
    tenant_id integer,
    internal_id character varying(50),
    name character varying(100),
    code character varying(50)
);


--
-- Name: dim_aggregator_aggregator_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.dim_aggregator_aggregator_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: dim_aggregator_aggregator_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.dim_aggregator_aggregator_id_seq OWNED BY public.dim_aggregator.aggregator_id;


--
-- Name: dim_bank_account; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.dim_bank_account (
    account_id bigint NOT NULL,
    tenant_id integer NOT NULL,
    store_id bigint,
    bank_name character varying(100),
    account_number character varying(50),
    swift_code character varying(50),
    iban character varying(50)
);


--
-- Name: dim_bank_account_account_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.dim_bank_account_account_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: dim_bank_account_account_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.dim_bank_account_account_id_seq OWNED BY public.dim_bank_account.account_id;


--
-- Name: dim_merchant; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.dim_merchant (
    merchant_id bigint NOT NULL,
    tenant_id integer NOT NULL,
    internal_id character varying(50),
    mid character varying(50),
    name character varying(150),
    status character varying(50),
    created_date timestamp without time zone,
    date_of_onboarding timestamp without time zone,
    sales_user_id character varying(50),
    sales_email character varying(100),
    referral_partner character varying(100),
    risk_level character varying(20),
    industry character varying(100),
    mcc character varying(10),
    location character varying(100),
    city character varying(100),
    contact_email character varying(255),
    generate_report_flag integer DEFAULT 1,
    rental_amount numeric(19,4)
);


--
-- Name: dim_merchant_merchant_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.dim_merchant_merchant_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: dim_merchant_merchant_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.dim_merchant_merchant_id_seq OWNED BY public.dim_merchant.merchant_id;


--
-- Name: dim_store; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.dim_store (
    store_id bigint NOT NULL,
    tenant_id integer NOT NULL,
    internal_id character varying(50),
    merchant_id bigint,
    sid character varying(50),
    name character varying(150),
    legal_name character varying(150),
    address text,
    city character varying(100),
    state character varying(100),
    postal_code character varying(50),
    mcc character varying(10),
    status character varying(50),
    created_date timestamp without time zone,
    latitude double precision,
    longitude double precision,
    timezone character varying(50),
    operating_hours jsonb,
    rental_amount numeric(19,4)
);


--
-- Name: dim_store_store_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.dim_store_store_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: dim_store_store_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.dim_store_store_id_seq OWNED BY public.dim_store.store_id;


--
-- Name: dim_terminal; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.dim_terminal (
    terminal_id bigint NOT NULL,
    tenant_id integer NOT NULL,
    internal_id character varying(50),
    store_id bigint,
    tid character varying(50),
    device_number character varying(50),
    type character varying(50),
    status character varying(50),
    created_date timestamp without time zone,
    rental_amount numeric(19,4)
);


--
-- Name: dim_terminal_terminal_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.dim_terminal_terminal_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: dim_terminal_terminal_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.dim_terminal_terminal_id_seq OWNED BY public.dim_terminal.terminal_id;


--
-- Name: ecom_flat_fee; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ecom_flat_fee (
    id bigint NOT NULL,
    tenant_id integer,
    country_code character varying(2) NOT NULL,
    fee_amount numeric(19,4) NOT NULL,
    label character varying(80),
    gateway_type character varying(60),
    is_default boolean DEFAULT false NOT NULL
);


--
-- Name: COLUMN ecom_flat_fee.gateway_type; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.ecom_flat_fee.gateway_type IS 'Raw terminal type (dim_terminal.type, UPPERCASE) this fee applies to, e.g. BENEFIT PG / MPGS / PAY ON. NULL = last-resort fallback for any ECOM gateway without its own row.';


--
-- Name: COLUMN ecom_flat_fee.is_default; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.ecom_flat_fee.is_default IS 'TRUE = this gateway''s fee applies to every ECOM terminal type with no exact gateway_type row (business rule 2026-08-28: default gateway is MPGS). At most one default per country/tenant.';


--
-- Name: ecom_flat_fee_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.ecom_flat_fee_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ecom_flat_fee_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.ecom_flat_fee_id_seq OWNED BY public.ecom_flat_fee.id;


--
-- Name: email_campaign; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.email_campaign (
    id bigint NOT NULL,
    tenant_id bigint NOT NULL,
    name character varying(255) NOT NULL,
    template_id bigint,
    campaign_type character varying(20) NOT NULL,
    recipient_filter_json text,
    attachment_type character varying(20) DEFAULT 'NONE'::character varying,
    attachment_report_template_id bigint,
    statement_month character varying(10),
    schedule_cron character varying(100),
    schedule_timezone character varying(50),
    status character varying(20) DEFAULT 'DRAFT'::character varying,
    total_recipients integer DEFAULT 0,
    sent_count integer DEFAULT 0,
    failed_count integer DEFAULT 0,
    sent_at timestamp without time zone,
    created_by character varying(255),
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone
);


--
-- Name: email_campaign_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.email_campaign_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: email_campaign_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.email_campaign_id_seq OWNED BY public.email_campaign.id;


--
-- Name: email_campaign_log; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.email_campaign_log (
    id bigint NOT NULL,
    campaign_id bigint NOT NULL,
    tenant_id bigint NOT NULL,
    merchant_id bigint,
    merchant_name character varying(255),
    recipient_email character varying(255),
    subject_rendered character varying(500),
    status character varying(20) NOT NULL,
    sent_at timestamp without time zone,
    error_message text,
    retry_count integer DEFAULT 0,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP
);


--
-- Name: email_campaign_log_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.email_campaign_log_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: email_campaign_log_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.email_campaign_log_id_seq OWNED BY public.email_campaign_log.id;


--
-- Name: email_queue; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.email_queue (
    id bigint NOT NULL,
    recipient character varying(255) NOT NULL,
    subject character varying(500),
    body text,
    attachment_path character varying(1000),
    status character varying(20) DEFAULT 'PENDING'::character varying,
    error_message text,
    retry_count integer DEFAULT 0,
    created_at timestamp without time zone DEFAULT now(),
    sent_at timestamp without time zone,
    tenant_id bigint,
    merchant_id bigint,
    merchant_name character varying(200),
    is_html boolean DEFAULT true,
    statement_month character varying(10)
);


--
-- Name: email_queue_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.email_queue_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: email_queue_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.email_queue_id_seq OWNED BY public.email_queue.id;


--
-- Name: email_smtp_config; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.email_smtp_config (
    id bigint NOT NULL,
    tenant_id bigint NOT NULL,
    config_name character varying(255) NOT NULL,
    host character varying(255) NOT NULL,
    port integer DEFAULT 587 NOT NULL,
    username character varying(255),
    password character varying(1024),
    auth_enabled boolean DEFAULT true,
    starttls_enabled boolean DEFAULT true,
    ssl_enabled boolean DEFAULT false,
    from_address character varying(255),
    from_name character varying(255),
    reply_to character varying(255),
    connection_timeout integer DEFAULT 10000,
    read_timeout integer DEFAULT 10000,
    write_timeout integer DEFAULT 10000,
    rate_limit_ms integer DEFAULT 200,
    max_retries integer DEFAULT 3,
    is_active boolean DEFAULT false,
    auto_send_after_batch boolean DEFAULT false,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP
);


--
-- Name: email_smtp_config_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.email_smtp_config_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: email_smtp_config_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.email_smtp_config_id_seq OWNED BY public.email_smtp_config.id;


--
-- Name: email_template_config; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.email_template_config (
    id bigint NOT NULL,
    tenant_id bigint NOT NULL,
    name character varying(255) NOT NULL,
    template_type character varying(50) NOT NULL,
    subject_template character varying(500) NOT NULL,
    body_html text NOT NULL,
    body_text text,
    is_active boolean DEFAULT true,
    is_default_for_type boolean DEFAULT false,
    created_by character varying(255),
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone
);


--
-- Name: email_template_config_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.email_template_config_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: email_template_config_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.email_template_config_id_seq OWNED BY public.email_template_config.id;


--
-- Name: event_outbox; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.event_outbox (
    id bigint NOT NULL,
    event_type character varying(100) NOT NULL,
    tenant_id bigint,
    payload text NOT NULL,
    status character varying(16) DEFAULT 'PENDING'::character varying NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    processed_at timestamp without time zone,
    last_error text
);


--
-- Name: event_outbox_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.event_outbox_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: event_outbox_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.event_outbox_id_seq OWNED BY public.event_outbox.id;


--
-- Name: explorer_alert; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.explorer_alert (
    id bigint NOT NULL,
    tenant_id bigint NOT NULL,
    name character varying(160) NOT NULL,
    measure_key character varying(120) NOT NULL,
    calc_json text,
    filter_json text,
    window_days integer DEFAULT 1,
    operator character varying(4) NOT NULL,
    threshold double precision NOT NULL,
    severity character varying(20) DEFAULT 'WARNING'::character varying,
    recipients text,
    is_enabled boolean DEFAULT true,
    last_value double precision,
    last_checked_at timestamp without time zone,
    last_triggered_at timestamp without time zone,
    created_by character varying(120),
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP
);


--
-- Name: explorer_alert_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.explorer_alert_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: explorer_alert_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.explorer_alert_id_seq OWNED BY public.explorer_alert.id;


--
-- Name: explorer_master_item; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.explorer_master_item (
    id bigint NOT NULL,
    tenant_id bigint NOT NULL,
    item_type character varying(20) NOT NULL,
    item_key character varying(120) NOT NULL,
    label character varying(160) NOT NULL,
    definition text,
    description character varying(255),
    created_by character varying(120),
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP
);


--
-- Name: explorer_master_item_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.explorer_master_item_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: explorer_master_item_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.explorer_master_item_id_seq OWNED BY public.explorer_master_item.id;


--
-- Name: fact_dcc_revenue; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.fact_dcc_revenue (
    dcc_id bigint NOT NULL,
    tenant_id integer NOT NULL,
    merchant_id bigint,
    store_id bigint,
    sid character varying(50),
    merchant_share numeric(19,4) NOT NULL,
    acquirer_share numeric(19,4) NOT NULL,
    payment_date date NOT NULL,
    file_id bigint,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP
);


--
-- Name: fact_dcc_revenue_dcc_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.fact_dcc_revenue_dcc_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: fact_dcc_revenue_dcc_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.fact_dcc_revenue_dcc_id_seq OWNED BY public.fact_dcc_revenue.dcc_id;


--
-- Name: fact_rental; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.fact_rental (
    rental_id bigint NOT NULL,
    tenant_id integer NOT NULL,
    level character varying(20) NOT NULL,
    merchant_id bigint,
    store_id bigint,
    terminal_id bigint,
    mid character varying(50),
    sid character varying(50),
    tid character varying(50),
    rental_amount numeric(19,4) NOT NULL,
    payment_date date NOT NULL,
    row_hash character varying(64),
    file_id bigint,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP
);


--
-- Name: fact_rental_rental_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.fact_rental_rental_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: fact_rental_rental_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.fact_rental_rental_id_seq OWNED BY public.fact_rental.rental_id;


--
-- Name: fact_transaction; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.fact_transaction (
    transaction_id bigint NOT NULL,
    tenant_id integer NOT NULL,
    merchant_id bigint,
    store_id bigint,
    terminal_id bigint,
    arn character varying(100),
    rrn_number character varying(100),
    card_number character varying(50),
    auth_code character varying(50),
    payment_date timestamp without time zone NOT NULL,
    transaction_date timestamp without time zone,
    batch_number character varying(50),
    transaction_type character varying(50),
    card_scheme character varying(50),
    card_type character varying(50),
    dcc boolean,
    txn_currency character varying(10),
    txn_currency_amount numeric(21,4),
    store_base_currency character varying(10),
    store_base_currency_amount numeric(21,4),
    msf numeric(19,4),
    vat numeric(19,4),
    total_amount_settled numeric(21,4),
    interchange_fee numeric(19,4),
    destination character varying(50),
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    scheme_fee numeric(19,4),
    ecom_fee numeric(19,4),
    card_product_code character varying(20),
    destination_raw character varying(40),
    channel character varying(20),
    fee_resolution_status character varying(30),
    interchange_rule_id bigint,
    scheme_fee_rule_id bigint,
    interchange_pct_applied numeric(9,6),
    interchange_flat_applied numeric(19,4),
    interchange_cap_applied numeric(19,4),
    scheme_fee_status character varying(30),
    issuer_bank character varying(100),
    issuer_country character varying(10)
)
PARTITION BY RANGE (payment_date);


--
-- Name: fact_transaction_transaction_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.fact_transaction_transaction_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: fact_transaction_transaction_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.fact_transaction_transaction_id_seq OWNED BY public.fact_transaction.transaction_id;


--
-- Name: fact_transaction_default; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.fact_transaction_default (
    transaction_id bigint DEFAULT nextval('public.fact_transaction_transaction_id_seq'::regclass) CONSTRAINT fact_transaction_transaction_id_not_null NOT NULL,
    tenant_id integer CONSTRAINT fact_transaction_tenant_id_not_null NOT NULL,
    merchant_id bigint,
    store_id bigint,
    terminal_id bigint,
    arn character varying(100),
    rrn_number character varying(100),
    card_number character varying(50),
    auth_code character varying(50),
    payment_date timestamp without time zone CONSTRAINT fact_transaction_payment_date_not_null NOT NULL,
    transaction_date timestamp without time zone,
    batch_number character varying(50),
    transaction_type character varying(50),
    card_scheme character varying(50),
    card_type character varying(50),
    dcc boolean,
    txn_currency character varying(10),
    txn_currency_amount numeric(21,4),
    store_base_currency character varying(10),
    store_base_currency_amount numeric(21,4),
    msf numeric(19,4),
    vat numeric(19,4),
    total_amount_settled numeric(21,4),
    interchange_fee numeric(19,4),
    destination character varying(50),
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    scheme_fee numeric(19,4),
    ecom_fee numeric(19,4),
    card_product_code character varying(20),
    destination_raw character varying(40),
    channel character varying(20),
    fee_resolution_status character varying(30),
    interchange_rule_id bigint,
    scheme_fee_rule_id bigint,
    interchange_pct_applied numeric(9,6),
    interchange_flat_applied numeric(19,4),
    interchange_cap_applied numeric(19,4),
    scheme_fee_status character varying(30),
    issuer_bank character varying(100),
    issuer_country character varying(10)
);


--
-- Name: feed_amount_contract; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.feed_amount_contract (
    id bigint NOT NULL,
    tenant_id integer,
    country_code character varying(2),
    column_name character varying(40) NOT NULL,
    unit_mode character varying(20) NOT NULL,
    note character varying(160)
);


--
-- Name: feed_amount_contract_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.feed_amount_contract_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: feed_amount_contract_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.feed_amount_contract_id_seq OWNED BY public.feed_amount_contract.id;


--
-- Name: fx_rate_daily; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.fx_rate_daily (
    fx_rate_id bigint NOT NULL,
    currency_code character varying(3) NOT NULL,
    rate_date date NOT NULL,
    units_per_usd numeric(18,8) NOT NULL,
    usd_per_unit numeric(20,12) GENERATED ALWAYS AS (((1)::numeric / units_per_usd)) STORED,
    source character varying(16) NOT NULL,
    source_ref character varying(200),
    loaded_by character varying(100),
    loaded_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT fx_rate_daily_units_per_usd_check CHECK ((units_per_usd > (0)::numeric))
);


--
-- Name: fx_rate_daily_fx_rate_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.fx_rate_daily_fx_rate_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: fx_rate_daily_fx_rate_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.fx_rate_daily_fx_rate_id_seq OWNED BY public.fx_rate_daily.fx_rate_id;


--
-- Name: ingest_day_coverage; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ingest_day_coverage (
    tenant_id bigint NOT NULL,
    txn_date date NOT NULL,
    rows_fact bigint,
    rows_summary bigint,
    gross_amount numeric(21,4),
    fee_priced_rows bigint,
    last_run_id bigint,
    last_loaded_at timestamp without time zone,
    load_count integer DEFAULT 1
);


--
-- Name: ingest_expectation; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ingest_expectation (
    tenant_id bigint NOT NULL,
    expected_daily boolean DEFAULT true,
    cutoff_local_time time without time zone DEFAULT '09:00:00'::time without time zone,
    timezone character varying(64) DEFAULT 'Asia/Bahrain'::character varying,
    sla_minutes integer DEFAULT 45,
    min_rows_warn bigint,
    variance_pct integer DEFAULT 40,
    fee_coverage_pct numeric(5,2) DEFAULT 95.00,
    enabled boolean DEFAULT false
);


--
-- Name: ingest_run; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ingest_run (
    id bigint NOT NULL,
    tenant_id bigint NOT NULL,
    source character varying(24) NOT NULL,
    job_execution_id bigint,
    job_name character varying(64),
    file_name character varying(512),
    file_bytes bigint,
    file_sha256 character(64),
    load_mode character varying(16),
    status character varying(16) NOT NULL,
    started_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    ended_at timestamp without time zone,
    duration_ms bigint,
    rows_file bigint,
    rows_staged bigint,
    rows_facted bigint,
    rows_summarised bigint,
    rows_rejected bigint,
    fact_rows_deleted bigint,
    min_txn_date date,
    max_txn_date date,
    distinct_days integer,
    unresolved_merchants integer,
    fee_priced_pct numeric(5,2),
    recon_status character varying(16),
    recon_detail text,
    error_class character varying(255),
    error_message text,
    triggered_by character varying(128),
    correlation_id character varying(64),
    acknowledged_by character varying(128),
    acknowledged_at timestamp without time zone,
    ack_note text
);


--
-- Name: ingest_run_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.ingest_run_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ingest_run_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.ingest_run_id_seq OWNED BY public.ingest_run.id;


--
-- Name: ingest_run_stage; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ingest_run_stage (
    id bigint NOT NULL,
    run_id bigint NOT NULL,
    stage_name character varying(64) NOT NULL,
    seq integer NOT NULL,
    status character varying(16),
    started_at timestamp without time zone,
    ended_at timestamp without time zone,
    duration_ms bigint,
    rows_in bigint,
    rows_out bigint,
    rows_skipped bigint,
    note text
);


--
-- Name: ingest_run_stage_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.ingest_run_stage_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ingest_run_stage_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.ingest_run_stage_id_seq OWNED BY public.ingest_run_stage.id;


--
-- Name: integration_connection; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.integration_connection (
    id bigint NOT NULL,
    tenant_id bigint NOT NULL,
    name character varying(255) NOT NULL,
    db_type character varying(50) NOT NULL,
    host character varying(255) NOT NULL,
    port integer NOT NULL,
    db_name character varying(255) NOT NULL,
    username character varying(255) NOT NULL,
    encrypted_password text NOT NULL,
    timeout_seconds integer DEFAULT 30,
    max_retries integer DEFAULT 3,
    is_active boolean DEFAULT true,
    last_test_at timestamp without time zone,
    last_test_status character varying(20),
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone,
    trust_server_cert boolean DEFAULT true
);


--
-- Name: integration_connection_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.integration_connection_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: integration_connection_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.integration_connection_id_seq OWNED BY public.integration_connection.id;


--
-- Name: integration_report; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.integration_report (
    id bigint NOT NULL,
    tenant_id bigint NOT NULL,
    connection_id bigint,
    name character varying(255) NOT NULL,
    report_type character varying(50) NOT NULL,
    sql_text text NOT NULL,
    column_mapping text,
    description text,
    param_schema text,
    is_active boolean DEFAULT true,
    approved_by character varying(255),
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone,
    amounts_minor_units boolean,
    approved_at timestamp without time zone
);


--
-- Name: integration_report_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.integration_report_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: integration_report_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.integration_report_id_seq OWNED BY public.integration_report.id;


--
-- Name: integration_run_log; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.integration_run_log (
    id bigint NOT NULL,
    tenant_id bigint NOT NULL,
    report_id bigint,
    schedule_id bigint,
    trigger_type character varying(20) NOT NULL,
    status character varying(20) NOT NULL,
    attempt_number integer DEFAULT 1,
    max_retries integer DEFAULT 3,
    rows_fetched integer DEFAULT 0,
    rows_processed integer DEFAULT 0,
    rows_failed integer DEFAULT 0,
    start_time timestamp without time zone NOT NULL,
    end_time timestamp without time zone,
    error_message text,
    date_range_from date,
    date_range_to date,
    duration_ms bigint,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP
);


--
-- Name: integration_run_log_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.integration_run_log_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: integration_run_log_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.integration_run_log_id_seq OWNED BY public.integration_run_log.id;


--
-- Name: integration_schedule; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.integration_schedule (
    id bigint NOT NULL,
    tenant_id bigint NOT NULL,
    report_id bigint,
    cron_expression character varying(100) NOT NULL,
    frequency_label character varying(50),
    timezone character varying(50) DEFAULT 'UTC'::character varying,
    is_enabled boolean DEFAULT true,
    last_run_at timestamp without time zone,
    next_run_at timestamp without time zone,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone,
    precondition_enabled boolean DEFAULT false,
    precondition_sql text,
    alert_emails text,
    alert_on_failure boolean DEFAULT true NOT NULL
);


--
-- Name: integration_schedule_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.integration_schedule_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: integration_schedule_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.integration_schedule_id_seq OWNED BY public.integration_schedule.id;


--
-- Name: interchange_file; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.interchange_file (
    id bigint NOT NULL,
    tenant_id bigint NOT NULL,
    ingest_run_id bigint,
    scheme character varying(12) NOT NULL,
    direction character varying(10) NOT NULL,
    format character varying(8) DEFAULT 'CTF'::character varying NOT NULL,
    file_name character varying(512) NOT NULL,
    file_bytes bigint,
    file_sha256 character(64),
    record_bytes integer DEFAULT 168 NOT NULL,
    record_count bigint DEFAULT 0 NOT NULL,
    batch_count integer DEFAULT 0 NOT NULL,
    transaction_count bigint DEFAULT 0 NOT NULL,
    monetary_count bigint DEFAULT 0 NOT NULL,
    processing_date date,
    settlement_date date,
    center_info_block character varying(8),
    test_file boolean DEFAULT false NOT NULL,
    status character varying(16) DEFAULT 'PARSED'::character varying NOT NULL,
    violation_count integer DEFAULT 0 NOT NULL,
    error_count integer DEFAULT 0 NOT NULL,
    warning_count integer DEFAULT 0 NOT NULL,
    balanced boolean,
    parsed_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    triggered_by character varying(128)
);


--
-- Name: interchange_file_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.interchange_file_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: interchange_file_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.interchange_file_id_seq OWNED BY public.interchange_file.id;


--
-- Name: interchange_normalization_detail; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.interchange_normalization_detail (
    detail_id bigint NOT NULL,
    run_id bigint NOT NULL,
    merchant_id bigint NOT NULL,
    merchant_name character varying(255),
    txn_count integer,
    txn_volume numeric(18,4),
    original_interchange numeric(18,4),
    weight_pct numeric(14,10),
    normalized_interchange numeric(18,4),
    difference numeric(18,4)
);


--
-- Name: interchange_normalization_detail_detail_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.interchange_normalization_detail_detail_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: interchange_normalization_detail_detail_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.interchange_normalization_detail_detail_id_seq OWNED BY public.interchange_normalization_detail.detail_id;


--
-- Name: interchange_normalization_run; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.interchange_normalization_run (
    run_id bigint NOT NULL,
    tenant_id bigint NOT NULL,
    month_key integer NOT NULL,
    version_no integer DEFAULT 1 NOT NULL,
    original_interchange_total numeric(18,4),
    target_normalized_total numeric(18,4) NOT NULL,
    difference numeric(18,4),
    unattributed_original numeric(18,4) DEFAULT 0,
    unattributed_normalized numeric(18,4) DEFAULT 0,
    weighting_base character varying(30) DEFAULT 'VOLUME_EXTRA'::character varying NOT NULL,
    residual_method character varying(30) DEFAULT 'LARGEST_REMAINDER'::character varying NOT NULL,
    currency_scale integer DEFAULT 2 NOT NULL,
    merchant_count integer,
    status character varying(20) DEFAULT 'PREVIEW'::character varying NOT NULL,
    status_detail character varying(500),
    created_by character varying(100),
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    applied_by character varying(100),
    applied_at timestamp without time zone
);


--
-- Name: interchange_normalization_run_run_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.interchange_normalization_run_run_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: interchange_normalization_run_run_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.interchange_normalization_run_run_id_seq OWNED BY public.interchange_normalization_run.run_id;


--
-- Name: interchange_rate_local; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.interchange_rate_local (
    id bigint NOT NULL,
    tenant_id integer,
    priority integer DEFAULT 0 NOT NULL,
    dest character varying(20) NOT NULL,
    channel character varying(20),
    scheme_group character varying(20),
    card_type character varying(20),
    tier character varying(20),
    mcc_sector character varying(20),
    min_ticket numeric(19,4),
    max_ticket numeric(19,4),
    interchange_pct numeric(9,6) NOT NULL,
    cap_amount numeric(19,4),
    label character varying(80),
    mcc character varying(10),
    country_code character varying(2) DEFAULT 'AE'::character varying NOT NULL,
    cap_currency_code character varying(3) DEFAULT 'AED'::character varying NOT NULL,
    flat_fee numeric(19,4) DEFAULT 0 NOT NULL,
    rate_status character varying(20) DEFAULT 'APPROVED'::character varying NOT NULL,
    effective_from date,
    effective_to date,
    source_note character varying(160)
);


--
-- Name: COLUMN interchange_rate_local.min_ticket; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.interchange_rate_local.min_ticket IS 'Inclusive lower ticket bound, in the TENANT SETTLEMENT currency (20 = 20 BHD on a BH tenant, 20 EGP on an EG one). Renamed from min_ticket_aed 2026-08-11.';


--
-- Name: COLUMN interchange_rate_local.max_ticket; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.interchange_rate_local.max_ticket IS 'Exclusive upper ticket bound, in the TENANT SETTLEMENT currency. Renamed from max_ticket_aed 2026-08-11.';


--
-- Name: COLUMN interchange_rate_local.flat_fee; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.interchange_rate_local.flat_fee IS 'Fixed per-transaction component ADDED to interchange_pct * amount, in cap_currency_code.';


--
-- Name: interchange_rate_local_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.interchange_rate_local_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: interchange_rate_local_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.interchange_rate_local_id_seq OWNED BY public.interchange_rate_local.id;


--
-- Name: interchange_transaction; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.interchange_transaction (
    id bigint NOT NULL,
    file_id bigint NOT NULL,
    tenant_id bigint NOT NULL,
    seq_in_file bigint NOT NULL,
    batch_number integer,
    transaction_code character varying(4) NOT NULL,
    tc_qualifier character varying(2),
    tcr_present character varying(16),
    account_masked character varying(24),
    acquirer_ref_number character varying(23),
    acquirer_bid character varying(8),
    purchase_date character varying(8),
    destination_amount numeric(18,2),
    destination_ccy character varying(3),
    source_amount numeric(18,2),
    source_ccy character varying(3),
    merchant_name character varying(25),
    merchant_country character varying(3),
    mcc character varying(4),
    fee_program_ind character varying(3),
    reimbursement_attr character varying(1),
    monetary boolean DEFAULT true NOT NULL,
    violation_count integer DEFAULT 0 NOT NULL
);


--
-- Name: interchange_transaction_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.interchange_transaction_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: interchange_transaction_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.interchange_transaction_id_seq OWNED BY public.interchange_transaction.id;


--
-- Name: interchange_violation; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.interchange_violation (
    id bigint NOT NULL,
    file_id bigint NOT NULL,
    transaction_id bigint,
    tenant_id bigint NOT NULL,
    record_no bigint,
    transaction_code character varying(4),
    tcr character varying(2),
    field_name character varying(64),
    field_position character varying(16),
    category character varying(24) NOT NULL,
    severity character varying(8) NOT NULL,
    requiredness character varying(1),
    rule_code character varying(48),
    message text NOT NULL,
    expected_value character varying(64),
    actual_value character varying(64)
);


--
-- Name: interchange_violation_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.interchange_violation_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: interchange_violation_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.interchange_violation_id_seq OWNED BY public.interchange_violation.id;


--
-- Name: kpi_snapshot_daily; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.kpi_snapshot_daily (
    snapshot_id bigint NOT NULL,
    tenant_id integer NOT NULL,
    snapshot_date date NOT NULL,
    metric_key character varying(50),
    metric_value numeric(21,4)
);


--
-- Name: kpi_snapshot_daily_snapshot_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.kpi_snapshot_daily_snapshot_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: kpi_snapshot_daily_snapshot_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.kpi_snapshot_daily_snapshot_id_seq OWNED BY public.kpi_snapshot_daily.snapshot_id;


--
-- Name: kpi_snapshot_monthly; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.kpi_snapshot_monthly (
    snapshot_id bigint NOT NULL,
    tenant_id integer NOT NULL,
    month_key integer NOT NULL,
    metric_key character varying(50),
    metric_value numeric(21,4)
);


--
-- Name: kpi_snapshot_monthly_snapshot_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.kpi_snapshot_monthly_snapshot_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: kpi_snapshot_monthly_snapshot_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.kpi_snapshot_monthly_snapshot_id_seq OWNED BY public.kpi_snapshot_monthly.snapshot_id;


--
-- Name: login_mfa_token; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.login_mfa_token (
    mfa_token_id bigint NOT NULL,
    user_id bigint NOT NULL,
    ticket character varying(255) NOT NULL,
    otp_hash character varying(255) NOT NULL,
    attempt_count integer DEFAULT 0,
    expires_at timestamp without time zone NOT NULL,
    used boolean DEFAULT false,
    ip_address character varying(64),
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP
);


--
-- Name: login_mfa_token_mfa_token_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.login_mfa_token_mfa_token_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: login_mfa_token_mfa_token_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.login_mfa_token_mfa_token_id_seq OWNED BY public.login_mfa_token.mfa_token_id;


--
-- Name: mcc_sector_map; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.mcc_sector_map (
    id bigint NOT NULL,
    tenant_id integer,
    mcc character varying(10) NOT NULL,
    sector character varying(20) NOT NULL,
    country_code character varying(2) DEFAULT 'AE'::character varying NOT NULL
);


--
-- Name: mcc_sector_map_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.mcc_sector_map_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: mcc_sector_map_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.mcc_sector_map_id_seq OWNED BY public.mcc_sector_map.id;


--
-- Name: merchant_activity; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.merchant_activity (
    activity_id bigint NOT NULL,
    tenant_id integer NOT NULL,
    merchant_id bigint,
    last_txn_date date,
    days_since_last_txn integer,
    status character varying(20)
);


--
-- Name: merchant_activity_activity_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.merchant_activity_activity_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: merchant_activity_activity_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.merchant_activity_activity_id_seq OWNED BY public.merchant_activity.activity_id;


--
-- Name: merchant_activity_summary; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.merchant_activity_summary (
    summary_id bigint NOT NULL,
    tenant_id integer NOT NULL,
    merchant_id bigint,
    calc_date date NOT NULL,
    first_txn_date timestamp without time zone,
    last_txn_date timestamp without time zone,
    last_7d_cnt integer DEFAULT 0,
    last_7d_value numeric(19,2) DEFAULT 0,
    last_30d_cnt integer DEFAULT 0,
    last_30d_value numeric(19,2) DEFAULT 0,
    status character varying(50),
    status_change_date timestamp without time zone
);


--
-- Name: merchant_activity_summary_summary_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.merchant_activity_summary_summary_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: merchant_activity_summary_summary_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.merchant_activity_summary_summary_id_seq OWNED BY public.merchant_activity_summary.summary_id;


--
-- Name: merchant_churn_score; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.merchant_churn_score (
    churn_id bigint NOT NULL,
    tenant_id integer NOT NULL,
    merchant_id bigint,
    calc_date date NOT NULL,
    churn_probability numeric(6,4) DEFAULT 0,
    risk_band character varying(10),
    top_reason character varying(255),
    model_version character varying(60),
    scored_by character varying(20) DEFAULT 'MODEL'::character varying,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP
);


--
-- Name: merchant_churn_score_churn_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.merchant_churn_score_churn_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: merchant_churn_score_churn_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.merchant_churn_score_churn_id_seq OWNED BY public.merchant_churn_score.churn_id;


--
-- Name: merchant_contact; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.merchant_contact (
    contact_id bigint NOT NULL,
    merchant_id bigint,
    store_id bigint,
    tenant_id integer NOT NULL,
    contact_name character varying(150),
    role character varying(150),
    email character varying(150),
    phone character varying(50),
    is_primary boolean DEFAULT false
);


--
-- Name: merchant_contact_contact_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.merchant_contact_contact_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: merchant_contact_contact_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.merchant_contact_contact_id_seq OWNED BY public.merchant_contact.contact_id;


--
-- Name: merchant_contract; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.merchant_contract (
    contract_id bigint NOT NULL,
    merchant_id bigint,
    tenant_id integer NOT NULL,
    contract_number character varying(100),
    start_date date,
    end_date date,
    auto_renew boolean DEFAULT false,
    status character varying(50)
);


--
-- Name: merchant_contract_contract_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.merchant_contract_contract_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: merchant_contract_contract_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.merchant_contract_contract_id_seq OWNED BY public.merchant_contract.contract_id;


--
-- Name: merchant_daily_metrics; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.merchant_daily_metrics (
    id bigint NOT NULL,
    tenant_id bigint NOT NULL,
    report_date date NOT NULL,
    merchant_id character varying(255) NOT NULL,
    merchant_name character varying(255),
    mid character varying(255),
    today_volume double precision DEFAULT 0.0,
    yesterday_volume double precision DEFAULT 0.0,
    avg7day double precision DEFAULT 0.0,
    total_mtd double precision DEFAULT 0.0,
    trend_pct double precision DEFAULT 0.0,
    volatility character varying(20),
    risk_score integer DEFAULT 0,
    ui_status character varying(20),
    daily_volumes_json text,
    sparkline_data_json text,
    source_type character varying(50),
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP
);


--
-- Name: merchant_daily_metrics_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.merchant_daily_metrics_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: merchant_daily_metrics_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.merchant_daily_metrics_id_seq OWNED BY public.merchant_daily_metrics.id;


--
-- Name: merchant_document; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.merchant_document (
    document_id bigint NOT NULL,
    merchant_id bigint,
    tenant_id integer NOT NULL,
    document_type character varying(50),
    document_name character varying(150),
    file_path character varying(255),
    upload_date timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    expiry_date timestamp without time zone
);


--
-- Name: merchant_document_document_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.merchant_document_document_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: merchant_document_document_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.merchant_document_document_id_seq OWNED BY public.merchant_document.document_id;


--
-- Name: merchant_lifecycle_status; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.merchant_lifecycle_status (
    merchant_id bigint,
    tenant_id integer NOT NULL,
    current_status character varying(50),
    reason_code character varying(50),
    last_status_change timestamp without time zone
);


--
-- Name: merchant_note; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.merchant_note (
    note_id bigint NOT NULL,
    merchant_id bigint,
    tenant_id integer NOT NULL,
    note_text text,
    created_by character varying(50),
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP
);


--
-- Name: merchant_note_note_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.merchant_note_note_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: merchant_note_note_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.merchant_note_note_id_seq OWNED BY public.merchant_note.note_id;


--
-- Name: merchant_opportunity_score; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.merchant_opportunity_score (
    score_id bigint NOT NULL,
    tenant_id integer NOT NULL,
    merchant_id bigint,
    score numeric(5,2),
    reason_tags character varying(255),
    calc_date date
);


--
-- Name: merchant_opportunity_score_score_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.merchant_opportunity_score_score_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: merchant_opportunity_score_score_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.merchant_opportunity_score_score_id_seq OWNED BY public.merchant_opportunity_score.score_id;


--
-- Name: merchant_risk_profile; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.merchant_risk_profile (
    profile_id bigint NOT NULL,
    merchant_id bigint,
    tenant_id integer NOT NULL,
    risk_score integer,
    compliance_status character varying(50),
    kyc_status character varying(50),
    aml_checks_passed boolean,
    last_review_date date,
    notes text
);


--
-- Name: merchant_risk_profile_profile_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.merchant_risk_profile_profile_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: merchant_risk_profile_profile_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.merchant_risk_profile_profile_id_seq OWNED BY public.merchant_risk_profile.profile_id;


--
-- Name: merchant_sales_assignment_history; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.merchant_sales_assignment_history (
    history_id bigint NOT NULL,
    tenant_id integer NOT NULL,
    merchant_id bigint NOT NULL,
    old_sales_user_id character varying(100),
    old_sales_email character varying(255),
    new_sales_user_id character varying(100) NOT NULL,
    new_sales_email character varying(255),
    changed_at timestamp without time zone DEFAULT now() NOT NULL,
    changed_by character varying(255),
    source character varying(30) DEFAULT 'UPLOAD'::character varying NOT NULL,
    job_execution_id bigint,
    upload_file_name character varying(500)
);


--
-- Name: merchant_sales_assignment_history_history_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.merchant_sales_assignment_history_history_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: merchant_sales_assignment_history_history_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.merchant_sales_assignment_history_history_id_seq OWNED BY public.merchant_sales_assignment_history.history_id;


--
-- Name: merchant_segment; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.merchant_segment (
    segment_id bigint NOT NULL,
    tenant_id integer NOT NULL,
    merchant_id bigint,
    calc_date date NOT NULL,
    primary_segment character varying(30),
    secondary_tags character varying(255),
    segment_reason character varying(255),
    segment_score numeric(5,2) DEFAULT 0,
    total_volume numeric(19,2),
    net_revenue numeric(19,2),
    net_margin_pct numeric(9,2),
    effective_bps numeric(9,2),
    net_take_bps numeric(9,2),
    volume_growth_pct numeric(9,2),
    days_since_last integer,
    model_version character varying(60),
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP
);


--
-- Name: merchant_segment_segment_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.merchant_segment_segment_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: merchant_segment_segment_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.merchant_segment_segment_id_seq OWNED BY public.merchant_segment.segment_id;


--
-- Name: merchant_settlement_config; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.merchant_settlement_config (
    config_id bigint NOT NULL,
    merchant_id bigint,
    tenant_id integer NOT NULL,
    settlement_frequency character varying(50),
    hold_days integer DEFAULT 0,
    min_settlement_amount numeric(19,2),
    currency character varying(10)
);


--
-- Name: merchant_settlement_config_config_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.merchant_settlement_config_config_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: merchant_settlement_config_config_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.merchant_settlement_config_config_id_seq OWNED BY public.merchant_settlement_config.config_id;


--
-- Name: mpe_file; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.mpe_file (
    id bigint NOT NULL,
    file_name character varying(200) NOT NULL,
    file_type character varying(10) NOT NULL,
    header_text character varying(80),
    created_date character varying(20),
    created_time character varying(20),
    sha256 character varying(64) NOT NULL,
    record_count integer,
    trailer_total integer,
    status character varying(20) NOT NULL,
    loaded_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    error_text text
);


--
-- Name: mpe_file_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.mpe_file_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: mpe_file_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.mpe_file_id_seq OWNED BY public.mpe_file.id;


--
-- Name: mpe_record; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.mpe_record (
    id bigint NOT NULL,
    file_id bigint NOT NULL,
    sub_id character varying(3) NOT NULL,
    active_flag character(1),
    effective_raw character varying(10),
    record_text text NOT NULL
);


--
-- Name: mpe_record_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.mpe_record_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: mpe_record_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.mpe_record_id_seq OWNED BY public.mpe_record.id;


--
-- Name: mpe_table_directory; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.mpe_table_directory (
    id bigint NOT NULL,
    file_id bigint NOT NULL,
    subject_table character varying(8) NOT NULL,
    subject_name character varying(30),
    sub_id character varying(3),
    key_length integer,
    key_start integer,
    rec_len_min integer,
    rec_len_max integer,
    version character varying(8),
    declared_count integer,
    staged_count integer
);


--
-- Name: mpe_table_directory_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.mpe_table_directory_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: mpe_table_directory_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.mpe_table_directory_id_seq OWNED BY public.mpe_table_directory.id;


--
-- Name: password_history; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.password_history (
    history_id bigint NOT NULL,
    user_id bigint NOT NULL,
    password_hash character varying(255) NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP
);


--
-- Name: password_history_history_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.password_history_history_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: password_history_history_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.password_history_history_id_seq OWNED BY public.password_history.history_id;


--
-- Name: password_reset_token; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.password_reset_token (
    token_id bigint NOT NULL,
    user_id bigint NOT NULL,
    token character varying(255) NOT NULL,
    expires_at timestamp without time zone NOT NULL,
    used boolean DEFAULT false,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    otp_hash character varying(255),
    attempt_count integer DEFAULT 0,
    verified boolean DEFAULT false
);


--
-- Name: password_reset_token_token_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.password_reset_token_token_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: password_reset_token_token_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.password_reset_token_token_id_seq OWNED BY public.password_reset_token.token_id;


--
-- Name: pdf_batch_log; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.pdf_batch_log (
    id bigint NOT NULL,
    job_id character varying(64) NOT NULL,
    tenant_id bigint,
    target_month character varying(10) NOT NULL,
    merchant_count integer DEFAULT 0 NOT NULL,
    status character varying(20) DEFAULT 'STARTED'::character varying NOT NULL,
    error_message text,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    completed_at timestamp without time zone
);


--
-- Name: pdf_batch_log_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.pdf_batch_log_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: pdf_batch_log_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.pdf_batch_log_id_seq OWNED BY public.pdf_batch_log.id;


--
-- Name: pdf_job_status; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.pdf_job_status (
    job_id character varying(80) NOT NULL,
    kind character varying(16) NOT NULL,
    tenant_id bigint NOT NULL,
    status_json text NOT NULL,
    finished boolean DEFAULT false NOT NULL,
    cancel_requested boolean DEFAULT false NOT NULL,
    started_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: profitability_adjustment_rate; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.profitability_adjustment_rate (
    id bigint NOT NULL,
    tenant_id integer NOT NULL,
    mid character varying(50),
    adj_code character varying(30) NOT NULL,
    pct_of_volume numeric(9,6) DEFAULT 0 NOT NULL,
    pct_of_msf numeric(9,6) DEFAULT 0 NOT NULL,
    per_txn_amount numeric(19,4) DEFAULT 0 NOT NULL,
    effective_from date DEFAULT '2000-01-01'::date NOT NULL,
    effective_to date,
    note character varying(200),
    updated_by character varying(100),
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT ck_profit_adj_code CHECK (((adj_code)::text = ANY ((ARRAY['PIE_MSF_REBATE'::character varying, 'KFCA_PROCESSING_FEE'::character varying, 'KFCA_PER_TXN_MARGIN'::character varying, 'ICPP_BILLING'::character varying])::text[])))
);


--
-- Name: profitability_adjustment_rate_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.profitability_adjustment_rate_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: profitability_adjustment_rate_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.profitability_adjustment_rate_id_seq OWNED BY public.profitability_adjustment_rate.id;


--
-- Name: rbac_version; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.rbac_version (
    tenant_id integer NOT NULL,
    version bigint DEFAULT 1 NOT NULL,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: ref_bin; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ref_bin (
    bin character varying(8) NOT NULL,
    scheme character varying(30),
    card_type character varying(20),
    product_code character varying(30),
    issuer_country character varying(2),
    issuer_name character varying(150),
    source_file character varying(200),
    loaded_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP
);


--
-- Name: ref_bin_product_tier; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ref_bin_product_tier (
    product_code character varying(5) NOT NULL,
    card_tier character varying(10) NOT NULL,
    note character varying(120),
    card_class character varying(12) DEFAULT 'CONSUMER'::character varying NOT NULL
);


--
-- Name: ref_bin_range; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ref_bin_range (
    id bigint NOT NULL,
    scheme character varying(20) NOT NULL,
    range_low character varying(19) NOT NULL,
    range_high character varying(19) NOT NULL,
    bin6 character varying(8),
    region_code character varying(2),
    issuer_country character varying(2),
    product_code character varying(5),
    funding_source character varying(2),
    card_type character varying(20),
    source_file character varying(200),
    loaded_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP
);


--
-- Name: ref_bin_range_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.ref_bin_range_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ref_bin_range_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.ref_bin_range_id_seq OWNED BY public.ref_bin_range.id;


--
-- Name: ref_card_scheme; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ref_card_scheme (
    id integer NOT NULL,
    is_active boolean DEFAULT true,
    code character varying(10) NOT NULL,
    name character varying(100),
    group_code character varying(10),
    group_name character varying(100),
    status integer DEFAULT 1,
    created_date timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    card_type integer,
    card_subtype integer
);


--
-- Name: ref_country; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ref_country (
    country_code character varying(2) NOT NULL,
    country_name character varying(100) NOT NULL,
    currency_code character varying(3) NOT NULL,
    currency_name character varying(50),
    currency_symbol character varying(5),
    phone_code character varying(10),
    iso_numeric character varying(3),
    iso3166_numeric character varying(3),
    decimal_notation_value integer DEFAULT 100
);


--
-- Name: ref_ecom_fx_rate; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ref_ecom_fx_rate (
    id bigint NOT NULL,
    tenant_id integer NOT NULL,
    mid character varying(64),
    txn_currency character varying(10) NOT NULL,
    board_rate numeric(12,6) NOT NULL,
    cost_rate numeric(12,6) NOT NULL,
    multiplier numeric(12,6) NOT NULL,
    label character varying(120)
);


--
-- Name: TABLE ref_ecom_fx_rate; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.ref_ecom_fx_rate IS 'ECOM FX income rates: fx = multiplier * (settlement/cost_rate - settlement/board_rate) per ECOM non-Benefit-PG transaction in this currency. mid NULL = tenant default; a mid row overrides. Read-time reference data - DB-seeded only, never uploadable.';


--
-- Name: ref_ecom_fx_rate_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.ref_ecom_fx_rate_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ref_ecom_fx_rate_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.ref_ecom_fx_rate_id_seq OWNED BY public.ref_ecom_fx_rate.id;


--
-- Name: ref_mcc_category; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ref_mcc_category (
    mcc character varying(4) NOT NULL,
    category character varying(100) NOT NULL
);


--
-- Name: ref_tenant_bin_bank; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ref_tenant_bin_bank (
    tenant_id integer NOT NULL,
    bin character varying(6) NOT NULL,
    bank_name character varying(128) NOT NULL,
    source_file character varying(256),
    loaded_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP
);


--
-- Name: refresh_token; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.refresh_token (
    id bigint NOT NULL,
    username character varying(100) NOT NULL,
    token_hash character varying(128) NOT NULL,
    issued_at timestamp without time zone DEFAULT now() NOT NULL,
    expires_at timestamp without time zone NOT NULL,
    revoked boolean DEFAULT false NOT NULL,
    replaced_by character varying(128),
    user_agent character varying(500),
    ip_address character varying(50)
);


--
-- Name: refresh_token_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.refresh_token_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: refresh_token_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.refresh_token_id_seq OWNED BY public.refresh_token.id;


--
-- Name: region; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.region (
    region_id integer NOT NULL,
    region_name character varying(100) NOT NULL
);


--
-- Name: region_region_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.region_region_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: region_region_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.region_region_id_seq OWNED BY public.region.region_id;


--
-- Name: report_cache_version; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.report_cache_version (
    tenant_id integer NOT NULL,
    version bigint DEFAULT 1 NOT NULL,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: report_query_config; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.report_query_config (
    id bigint NOT NULL,
    report_name character varying(255) NOT NULL,
    sql_text text NOT NULL,
    source_id bigint,
    description text,
    is_active boolean DEFAULT true,
    approved_by character varying(255),
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP
);


--
-- Name: report_query_config_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.report_query_config_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: report_query_config_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.report_query_config_id_seq OWNED BY public.report_query_config.id;


--
-- Name: report_run_log; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.report_run_log (
    id bigint NOT NULL,
    query_id bigint,
    start_time timestamp without time zone,
    end_time timestamp without time zone,
    status character varying(50),
    row_count integer,
    error_message text,
    tenant_id bigint,
    job_id character varying(64),
    merchant_count integer,
    target_month character varying(10)
);


--
-- Name: report_run_log_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.report_run_log_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: report_run_log_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.report_run_log_id_seq OWNED BY public.report_run_log.id;


--
-- Name: report_schedule; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.report_schedule (
    id bigint NOT NULL,
    tenant_id bigint NOT NULL,
    template_id bigint,
    cron_expression character varying(100) NOT NULL,
    frequency_label character varying(50),
    timezone character varying(50) DEFAULT 'UTC'::character varying,
    delivery_method character varying(20) DEFAULT 'EMAIL'::character varying,
    recipient_emails text,
    export_format character varying(10) DEFAULT 'EXCEL'::character varying,
    is_enabled boolean DEFAULT true,
    last_run_at timestamp without time zone,
    next_run_at timestamp without time zone,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone
);


--
-- Name: report_schedule_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.report_schedule_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: report_schedule_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.report_schedule_id_seq OWNED BY public.report_schedule.id;


--
-- Name: report_template; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.report_template (
    id bigint NOT NULL,
    tenant_id bigint NOT NULL,
    user_id bigint NOT NULL,
    name character varying(255) NOT NULL,
    description text,
    config_json text NOT NULL,
    is_shared boolean DEFAULT false,
    last_run_at timestamp without time zone,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone
);


--
-- Name: report_template_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.report_template_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: report_template_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.report_template_id_seq OWNED BY public.report_template.id;


--
-- Name: revenue_leakage_flags; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.revenue_leakage_flags (
    flag_id bigint NOT NULL,
    merchant_id bigint,
    tenant_id integer NOT NULL,
    check_type character varying(50),
    severity character varying(20),
    details text,
    detected_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    is_resolved boolean DEFAULT false,
    merchant_name character varying(200),
    business_date date,
    metric_value numeric(21,4),
    baseline_value numeric(21,4),
    delta_pct numeric(9,2),
    est_monthly_impact numeric(21,4),
    status character varying(20) DEFAULT 'OPEN'::character varying,
    resolved_at timestamp without time zone,
    resolved_by character varying(100)
);


--
-- Name: revenue_leakage_flags_flag_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.revenue_leakage_flags_flag_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: revenue_leakage_flags_flag_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.revenue_leakage_flags_flag_id_seq OWNED BY public.revenue_leakage_flags.flag_id;


--
-- Name: role; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.role (
    role_id integer NOT NULL,
    role_name character varying(50) NOT NULL
);


--
-- Name: role_role_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.role_role_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: role_role_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.role_role_id_seq OWNED BY public.role.role_id;


--
-- Name: sales_agent_profile; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sales_agent_profile (
    id bigint NOT NULL,
    tenant_id bigint NOT NULL,
    sales_user_id character varying(100) NOT NULL,
    sales_email character varying(255),
    display_name character varying(255),
    phone character varying(50),
    country_code character varying(10),
    hire_date date,
    monthly_target numeric(19,2),
    status character varying(30) DEFAULT 'ACTIVE'::character varying,
    notes text,
    created_at timestamp without time zone DEFAULT now(),
    updated_at timestamp without time zone DEFAULT now()
);


--
-- Name: sales_agent_profile_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.sales_agent_profile_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: sales_agent_profile_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.sales_agent_profile_id_seq OWNED BY public.sales_agent_profile.id;


--
-- Name: sales_agent_target; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sales_agent_target (
    target_id bigint NOT NULL,
    tenant_id integer NOT NULL,
    sales_user_id character varying(100) NOT NULL,
    month_key integer NOT NULL,
    target_value numeric(19,4),
    metric_type character varying(50) DEFAULT 'NET_REVENUE'::character varying NOT NULL,
    source character varying(20) DEFAULT 'MANUAL'::character varying NOT NULL,
    created_by character varying(100),
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP
);


--
-- Name: sales_agent_target_target_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.sales_agent_target_target_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: sales_agent_target_target_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.sales_agent_target_target_id_seq OWNED BY public.sales_agent_target.target_id;


--
-- Name: sales_country_lead; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sales_country_lead (
    id bigint NOT NULL,
    tenant_id bigint NOT NULL,
    country_lead_name character varying(255) NOT NULL,
    country_lead_email character varying(255) NOT NULL,
    country_code character varying(2),
    is_default boolean DEFAULT false,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP
);


--
-- Name: sales_country_lead_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.sales_country_lead_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: sales_country_lead_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.sales_country_lead_id_seq OWNED BY public.sales_country_lead.id;


--
-- Name: sales_team_mapping; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sales_team_mapping (
    id bigint NOT NULL,
    tenant_id bigint NOT NULL,
    team_lead_name character varying(100) NOT NULL,
    team_lead_email character varying(150) NOT NULL,
    is_default boolean DEFAULT false,
    created_at timestamp without time zone DEFAULT now(),
    country_lead_id bigint
);


--
-- Name: sales_team_mapping_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.sales_team_mapping_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: sales_team_mapping_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.sales_team_mapping_id_seq OWNED BY public.sales_team_mapping.id;


--
-- Name: sales_user_assignment; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sales_user_assignment (
    id bigint NOT NULL,
    tenant_id bigint NOT NULL,
    sales_user_id character varying(100) NOT NULL,
    team_lead_id bigint NOT NULL,
    assigned_at timestamp without time zone DEFAULT now()
);


--
-- Name: sales_user_assignment_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.sales_user_assignment_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: sales_user_assignment_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.sales_user_assignment_id_seq OWNED BY public.sales_user_assignment.id;


--
-- Name: saved_filter; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.saved_filter (
    id bigint NOT NULL,
    tenant_id integer NOT NULL,
    user_id bigint NOT NULL,
    name character varying(100) NOT NULL,
    dashboard_type character varying(50) NOT NULL,
    filter_json text NOT NULL,
    is_default boolean DEFAULT false,
    is_shared boolean DEFAULT false,
    created_at timestamp without time zone DEFAULT now(),
    updated_at timestamp without time zone DEFAULT now()
);


--
-- Name: saved_filter_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.saved_filter_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: saved_filter_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.saved_filter_id_seq OWNED BY public.saved_filter.id;


--
-- Name: schema_migration_log; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.schema_migration_log (
    filename character varying(200) NOT NULL,
    applied_at timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: schema_migration_registry; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.schema_migration_registry (
    registry_id bigint NOT NULL,
    migration_name character varying(255) NOT NULL,
    description text,
    applied_on_dev boolean DEFAULT false,
    applied_on_prod boolean DEFAULT false,
    applied_at timestamp without time zone,
    applied_by character varying(100),
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP
);


--
-- Name: schema_migration_registry_registry_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.schema_migration_registry_registry_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: schema_migration_registry_registry_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.schema_migration_registry_registry_id_seq OWNED BY public.schema_migration_registry.registry_id;


--
-- Name: scheme_fee_rate; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.scheme_fee_rate (
    id bigint NOT NULL,
    tenant_id integer,
    dest character varying(20) NOT NULL,
    channel character varying(20) NOT NULL,
    fee_pct numeric(9,6) NOT NULL,
    scheme_group character varying(20),
    country_code character varying(2) DEFAULT 'AE'::character varying NOT NULL,
    flat_fee numeric(19,4) DEFAULT 0 NOT NULL,
    rate_status character varying(20) DEFAULT 'APPROVED'::character varying NOT NULL,
    effective_from date,
    effective_to date,
    source_note character varying(160),
    card_type character varying(20)
);


--
-- Name: scheme_fee_rate_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.scheme_fee_rate_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: scheme_fee_rate_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.scheme_fee_rate_id_seq OWNED BY public.scheme_fee_rate.id;


--
-- Name: shedlock; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.shedlock (
    name character varying(64) NOT NULL,
    lock_until timestamp without time zone NOT NULL,
    locked_at timestamp without time zone NOT NULL,
    locked_by character varying(255) NOT NULL
);


--
-- Name: sso_state_token; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sso_state_token (
    state_token character varying(100) NOT NULL,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    expires_at timestamp without time zone NOT NULL,
    used boolean DEFAULT false NOT NULL
);


--
-- Name: stg_dcc_revenue_raw; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.stg_dcc_revenue_raw (
    raw_id bigint NOT NULL,
    tenant_id integer,
    file_id bigint,
    load_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    status character varying(20) DEFAULT 'PENDING'::character varying,
    error_message text,
    sid character varying(50),
    file_tenant_id character varying(50),
    merchant_share numeric(19,4),
    acquirer_share numeric(19,4),
    payment_date date,
    mid character varying(50)
);


--
-- Name: stg_dcc_revenue_raw_raw_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.stg_dcc_revenue_raw_raw_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: stg_dcc_revenue_raw_raw_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.stg_dcc_revenue_raw_raw_id_seq OWNED BY public.stg_dcc_revenue_raw.raw_id;


--
-- Name: stg_fx_rate_raw; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.stg_fx_rate_raw (
    stg_id bigint NOT NULL,
    tenant_id integer NOT NULL,
    currency_code character varying(3),
    rate_date date NOT NULL,
    units_per_usd numeric(18,8) NOT NULL,
    load_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: stg_fx_rate_raw_stg_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.stg_fx_rate_raw_stg_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: stg_fx_rate_raw_stg_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.stg_fx_rate_raw_stg_id_seq OWNED BY public.stg_fx_rate_raw.stg_id;


--
-- Name: stg_merchant_master_raw; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.stg_merchant_master_raw (
    raw_id bigint NOT NULL,
    tenant_id integer,
    file_id bigint,
    load_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    row_hash character varying(64),
    status character varying(20) DEFAULT 'PENDING'::character varying,
    error_message text,
    institution_code character varying(50),
    institution_name character varying(100),
    entity_internal_id character varying(50),
    entity_name character varying(100),
    entity_code character varying(50),
    aggregator_internal_id character varying(50),
    aggregator_name character varying(100),
    aggregator_code character varying(50),
    merchant_internal_id character varying(50),
    mid character varying(50),
    merchant_name character varying(150),
    merchant_status character varying(50),
    merchant_store_internal_id character varying(50),
    sid character varying(50),
    store_legal_name character varying(150),
    store_name character varying(150),
    store_status character varying(50),
    business_type character varying(100),
    business_mcc character varying(10),
    vat_number character varying(50),
    primary_contact_person character varying(100),
    primary_contact_number character varying(50),
    primary_contact_email character varying(100),
    primary_contact_designation character varying(100),
    secondary_contact_person character varying(100),
    secondary_contact_email character varying(100),
    secondary_contact_number character varying(50),
    secondary_contact_designation character varying(100),
    address text,
    city character varying(100),
    state character varying(100),
    postal_code character varying(20),
    store_desc text,
    industry_type character varying(100),
    customer_type character varying(100),
    source_of_fund character varying(100),
    expected_volume numeric(19,2),
    regulated_activity boolean,
    regulated_activity_desc text,
    auditor_name character varying(100),
    is_pep boolean,
    pep_reason text,
    high_risk_adverse_media boolean,
    high_risk_source_of_wealth boolean,
    risk_level character varying(20),
    risk_level_high boolean,
    risk_level_prohibited boolean,
    risk_level_restricted boolean,
    product character varying(100),
    date_of_onboarding timestamp without time zone,
    reviewed_date timestamp without time zone,
    next_reviewed_date timestamp without time zone,
    sales_user_email character varying(100),
    sales_user_id character varying(50),
    referral_partner character varying(100),
    created_date timestamp without time zone,
    terminal_internal_id character varying(50),
    tid character varying(50),
    terminal_name character varying(100),
    terminal_status character varying(50),
    terminal_device_number character varying(50),
    terminal_type character varying(50),
    terminal_description text,
    bank_name character varying(100),
    bank_account_name character varying(100),
    bank_account_number character varying(50),
    swift_code character varying(50),
    iban_number character varying(50),
    merchant_created_date timestamp without time zone,
    merchant_store_created_date timestamp without time zone,
    terminal_created_date timestamp without time zone
);


--
-- Name: stg_merchant_master_raw_raw_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.stg_merchant_master_raw_raw_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: stg_merchant_master_raw_raw_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.stg_merchant_master_raw_raw_id_seq OWNED BY public.stg_merchant_master_raw.raw_id;


--
-- Name: stg_rental_raw; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.stg_rental_raw (
    raw_id bigint NOT NULL,
    tenant_id integer,
    file_id bigint,
    load_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    row_hash character varying(64),
    status character varying(20) DEFAULT 'PENDING'::character varying,
    error_message text,
    entity_name character varying(100),
    mid character varying(50),
    sid character varying(50),
    tid character varying(50),
    level character varying(20),
    rental_amount numeric(19,4),
    payment_date date
);


--
-- Name: stg_rental_raw_raw_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.stg_rental_raw_raw_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: stg_rental_raw_raw_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.stg_rental_raw_raw_id_seq OWNED BY public.stg_rental_raw.raw_id;


--
-- Name: stg_trnx_raw; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.stg_trnx_raw (
    raw_id bigint NOT NULL,
    tenant_id integer,
    file_id bigint,
    load_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    row_hash character varying(64),
    status character varying(20) DEFAULT 'PENDING'::character varying,
    error_message text,
    entity_name character varying(100),
    aggregator_internal_id character varying(50),
    aggregator_name character varying(100),
    aggregator_code character varying(50),
    mid character varying(50),
    merchant_internal_id character varying(50),
    merchant_name character varying(150),
    sid character varying(50),
    merchant_store_internal_id character varying(50),
    cmm_merchant_store_internal_id character varying(50),
    merchant_store_legal_name character varying(150),
    store_name character varying(150),
    tid character varying(50),
    arn character varying(100),
    rrn_number character varying(100),
    card_number character varying(50),
    auth_code character varying(50),
    payment_date timestamp without time zone,
    transaction_date timestamp without time zone,
    batch_number character varying(50),
    transaction_type character varying(50),
    card_scheme character varying(50),
    card_type character varying(50),
    dcc boolean,
    txn_currency character varying(10),
    txn_currency_amount numeric(21,4),
    store_base_currency character varying(10),
    store_base_currency_amount numeric(21,4),
    msf numeric(19,4),
    vat numeric(19,4),
    total_amount_settled numeric(21,4),
    interchange_fee numeric(19,4),
    destination character varying(50),
    card_product_code character varying(20),
    issuer_country character varying(10),
    ingest_run_id bigint
);


--
-- Name: stg_trnx_raw_raw_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.stg_trnx_raw_raw_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: stg_trnx_raw_raw_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.stg_trnx_raw_raw_id_seq OWNED BY public.stg_trnx_raw.raw_id;


--
-- Name: stg_visa_clearing_raw; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.stg_visa_clearing_raw (
    id bigint NOT NULL,
    ingest_run_id bigint NOT NULL,
    tenant_id bigint NOT NULL,
    file_name character varying(512) NOT NULL,
    record_no bigint NOT NULL,
    transaction_code character varying(4) NOT NULL,
    tcr character varying(2) NOT NULL,
    raw_record character varying(200) NOT NULL,
    loaded_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: stg_visa_clearing_raw_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.stg_visa_clearing_raw_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: stg_visa_clearing_raw_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.stg_visa_clearing_raw_id_seq OWNED BY public.stg_visa_clearing_raw.id;


--
-- Name: sum_daily_bank; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sum_daily_bank (
    summary_id bigint NOT NULL,
    tenant_id integer NOT NULL,
    business_date date NOT NULL,
    total_txns bigint DEFAULT 0,
    total_volume numeric(21,4) DEFAULT 0,
    total_msf numeric(21,4) DEFAULT 0,
    total_interchange numeric(21,4) DEFAULT 0,
    total_scheme_fee numeric(21,4) DEFAULT 0,
    total_vat numeric(21,4) DEFAULT 0,
    total_net_revenue numeric(21,4) DEFAULT 0,
    total_ecom_fee numeric(21,4) DEFAULT 0,
    total_base_volume numeric(21,4) DEFAULT 0
)
PARTITION BY RANGE (business_date);


--
-- Name: sum_daily_bank_summary_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.sum_daily_bank_summary_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: sum_daily_bank_summary_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.sum_daily_bank_summary_id_seq OWNED BY public.sum_daily_bank.summary_id;


--
-- Name: sum_daily_bank_default; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sum_daily_bank_default (
    summary_id bigint DEFAULT nextval('public.sum_daily_bank_summary_id_seq'::regclass) CONSTRAINT sum_daily_bank_summary_id_not_null NOT NULL,
    tenant_id integer CONSTRAINT sum_daily_bank_tenant_id_not_null NOT NULL,
    business_date date CONSTRAINT sum_daily_bank_business_date_not_null NOT NULL,
    total_txns bigint DEFAULT 0,
    total_volume numeric(21,4) DEFAULT 0,
    total_msf numeric(21,4) DEFAULT 0,
    total_interchange numeric(21,4) DEFAULT 0,
    total_scheme_fee numeric(21,4) DEFAULT 0,
    total_vat numeric(21,4) DEFAULT 0,
    total_net_revenue numeric(21,4) DEFAULT 0,
    total_ecom_fee numeric(21,4) DEFAULT 0,
    total_base_volume numeric(21,4) DEFAULT 0
);


--
-- Name: sum_daily_bank_y2024; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sum_daily_bank_y2024 (
    summary_id bigint DEFAULT nextval('public.sum_daily_bank_summary_id_seq'::regclass) CONSTRAINT sum_daily_bank_summary_id_not_null NOT NULL,
    tenant_id integer CONSTRAINT sum_daily_bank_tenant_id_not_null NOT NULL,
    business_date date CONSTRAINT sum_daily_bank_business_date_not_null NOT NULL,
    total_txns bigint DEFAULT 0,
    total_volume numeric(21,4) DEFAULT 0,
    total_msf numeric(21,4) DEFAULT 0,
    total_interchange numeric(21,4) DEFAULT 0,
    total_scheme_fee numeric(21,4) DEFAULT 0,
    total_vat numeric(21,4) DEFAULT 0,
    total_net_revenue numeric(21,4) DEFAULT 0,
    total_ecom_fee numeric(21,4) DEFAULT 0,
    total_base_volume numeric(21,4) DEFAULT 0
);


--
-- Name: sum_daily_bank_y2025; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sum_daily_bank_y2025 (
    summary_id bigint DEFAULT nextval('public.sum_daily_bank_summary_id_seq'::regclass) CONSTRAINT sum_daily_bank_summary_id_not_null NOT NULL,
    tenant_id integer CONSTRAINT sum_daily_bank_tenant_id_not_null NOT NULL,
    business_date date CONSTRAINT sum_daily_bank_business_date_not_null NOT NULL,
    total_txns bigint DEFAULT 0,
    total_volume numeric(21,4) DEFAULT 0,
    total_msf numeric(21,4) DEFAULT 0,
    total_interchange numeric(21,4) DEFAULT 0,
    total_scheme_fee numeric(21,4) DEFAULT 0,
    total_vat numeric(21,4) DEFAULT 0,
    total_net_revenue numeric(21,4) DEFAULT 0,
    total_ecom_fee numeric(21,4) DEFAULT 0,
    total_base_volume numeric(21,4) DEFAULT 0
);


--
-- Name: sum_daily_bank_y2026; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sum_daily_bank_y2026 (
    summary_id bigint DEFAULT nextval('public.sum_daily_bank_summary_id_seq'::regclass) CONSTRAINT sum_daily_bank_summary_id_not_null NOT NULL,
    tenant_id integer CONSTRAINT sum_daily_bank_tenant_id_not_null NOT NULL,
    business_date date CONSTRAINT sum_daily_bank_business_date_not_null NOT NULL,
    total_txns bigint DEFAULT 0,
    total_volume numeric(21,4) DEFAULT 0,
    total_msf numeric(21,4) DEFAULT 0,
    total_interchange numeric(21,4) DEFAULT 0,
    total_scheme_fee numeric(21,4) DEFAULT 0,
    total_vat numeric(21,4) DEFAULT 0,
    total_net_revenue numeric(21,4) DEFAULT 0,
    total_ecom_fee numeric(21,4) DEFAULT 0,
    total_base_volume numeric(21,4) DEFAULT 0
);


--
-- Name: sum_daily_bank_y2027; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sum_daily_bank_y2027 (
    summary_id bigint DEFAULT nextval('public.sum_daily_bank_summary_id_seq'::regclass) CONSTRAINT sum_daily_bank_summary_id_not_null NOT NULL,
    tenant_id integer CONSTRAINT sum_daily_bank_tenant_id_not_null NOT NULL,
    business_date date CONSTRAINT sum_daily_bank_business_date_not_null NOT NULL,
    total_txns bigint DEFAULT 0,
    total_volume numeric(21,4) DEFAULT 0,
    total_msf numeric(21,4) DEFAULT 0,
    total_interchange numeric(21,4) DEFAULT 0,
    total_scheme_fee numeric(21,4) DEFAULT 0,
    total_vat numeric(21,4) DEFAULT 0,
    total_net_revenue numeric(21,4) DEFAULT 0,
    total_ecom_fee numeric(21,4) DEFAULT 0,
    total_base_volume numeric(21,4) DEFAULT 0
);


--
-- Name: sum_daily_channel; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sum_daily_channel (
    summary_id bigint NOT NULL,
    tenant_id integer NOT NULL,
    business_date date NOT NULL,
    channel character varying(50),
    total_txns bigint DEFAULT 0,
    total_volume numeric(21,4) DEFAULT 0,
    total_msf numeric(21,4) DEFAULT 0,
    total_interchange numeric(21,4) DEFAULT 0,
    total_scheme_fee numeric(21,4) DEFAULT 0,
    total_net_revenue numeric(21,4) DEFAULT 0
)
PARTITION BY RANGE (business_date);


--
-- Name: sum_daily_channel_summary_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.sum_daily_channel_summary_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: sum_daily_channel_summary_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.sum_daily_channel_summary_id_seq OWNED BY public.sum_daily_channel.summary_id;


--
-- Name: sum_daily_channel_default; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sum_daily_channel_default (
    summary_id bigint DEFAULT nextval('public.sum_daily_channel_summary_id_seq'::regclass) CONSTRAINT sum_daily_channel_summary_id_not_null NOT NULL,
    tenant_id integer CONSTRAINT sum_daily_channel_tenant_id_not_null NOT NULL,
    business_date date CONSTRAINT sum_daily_channel_business_date_not_null NOT NULL,
    channel character varying(50),
    total_txns bigint DEFAULT 0,
    total_volume numeric(21,4) DEFAULT 0,
    total_msf numeric(21,4) DEFAULT 0,
    total_interchange numeric(21,4) DEFAULT 0,
    total_scheme_fee numeric(21,4) DEFAULT 0,
    total_net_revenue numeric(21,4) DEFAULT 0
);


--
-- Name: sum_daily_channel_y2024; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sum_daily_channel_y2024 (
    summary_id bigint DEFAULT nextval('public.sum_daily_channel_summary_id_seq'::regclass) CONSTRAINT sum_daily_channel_summary_id_not_null NOT NULL,
    tenant_id integer CONSTRAINT sum_daily_channel_tenant_id_not_null NOT NULL,
    business_date date CONSTRAINT sum_daily_channel_business_date_not_null NOT NULL,
    channel character varying(50),
    total_txns bigint DEFAULT 0,
    total_volume numeric(21,4) DEFAULT 0,
    total_msf numeric(21,4) DEFAULT 0,
    total_interchange numeric(21,4) DEFAULT 0,
    total_scheme_fee numeric(21,4) DEFAULT 0,
    total_net_revenue numeric(21,4) DEFAULT 0
);


--
-- Name: sum_daily_channel_y2025; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sum_daily_channel_y2025 (
    summary_id bigint DEFAULT nextval('public.sum_daily_channel_summary_id_seq'::regclass) CONSTRAINT sum_daily_channel_summary_id_not_null NOT NULL,
    tenant_id integer CONSTRAINT sum_daily_channel_tenant_id_not_null NOT NULL,
    business_date date CONSTRAINT sum_daily_channel_business_date_not_null NOT NULL,
    channel character varying(50),
    total_txns bigint DEFAULT 0,
    total_volume numeric(21,4) DEFAULT 0,
    total_msf numeric(21,4) DEFAULT 0,
    total_interchange numeric(21,4) DEFAULT 0,
    total_scheme_fee numeric(21,4) DEFAULT 0,
    total_net_revenue numeric(21,4) DEFAULT 0
);


--
-- Name: sum_daily_channel_y2026; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sum_daily_channel_y2026 (
    summary_id bigint DEFAULT nextval('public.sum_daily_channel_summary_id_seq'::regclass) CONSTRAINT sum_daily_channel_summary_id_not_null NOT NULL,
    tenant_id integer CONSTRAINT sum_daily_channel_tenant_id_not_null NOT NULL,
    business_date date CONSTRAINT sum_daily_channel_business_date_not_null NOT NULL,
    channel character varying(50),
    total_txns bigint DEFAULT 0,
    total_volume numeric(21,4) DEFAULT 0,
    total_msf numeric(21,4) DEFAULT 0,
    total_interchange numeric(21,4) DEFAULT 0,
    total_scheme_fee numeric(21,4) DEFAULT 0,
    total_net_revenue numeric(21,4) DEFAULT 0
);


--
-- Name: sum_daily_channel_y2027; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sum_daily_channel_y2027 (
    summary_id bigint DEFAULT nextval('public.sum_daily_channel_summary_id_seq'::regclass) CONSTRAINT sum_daily_channel_summary_id_not_null NOT NULL,
    tenant_id integer CONSTRAINT sum_daily_channel_tenant_id_not_null NOT NULL,
    business_date date CONSTRAINT sum_daily_channel_business_date_not_null NOT NULL,
    channel character varying(50),
    total_txns bigint DEFAULT 0,
    total_volume numeric(21,4) DEFAULT 0,
    total_msf numeric(21,4) DEFAULT 0,
    total_interchange numeric(21,4) DEFAULT 0,
    total_scheme_fee numeric(21,4) DEFAULT 0,
    total_net_revenue numeric(21,4) DEFAULT 0
);


--
-- Name: sum_daily_explorer; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sum_daily_explorer (
    summary_id bigint NOT NULL,
    tenant_id integer NOT NULL,
    business_date date NOT NULL,
    merchant_id bigint,
    store_id bigint,
    terminal_id bigint,
    transaction_type character varying(50),
    card_scheme character varying(50),
    card_type character varying(50),
    destination character varying(50),
    channel character varying(50),
    txn_currency character varying(10),
    store_base_currency character varying(10),
    is_opt_in boolean,
    total_txns bigint DEFAULT 0,
    total_txn_currency_amount numeric(21,4) DEFAULT 0,
    total_base_volume numeric(21,4) DEFAULT 0,
    total_msf numeric(21,4) DEFAULT 0,
    total_vat numeric(21,4) DEFAULT 0,
    total_settled numeric(21,4) DEFAULT 0,
    total_interchange numeric(21,4) DEFAULT 0,
    total_scheme_fee numeric(21,4) DEFAULT 0
)
PARTITION BY RANGE (business_date);


--
-- Name: sum_daily_explorer_summary_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.sum_daily_explorer_summary_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: sum_daily_explorer_summary_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.sum_daily_explorer_summary_id_seq OWNED BY public.sum_daily_explorer.summary_id;


--
-- Name: sum_daily_explorer_default; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sum_daily_explorer_default (
    summary_id bigint DEFAULT nextval('public.sum_daily_explorer_summary_id_seq'::regclass) CONSTRAINT sum_daily_explorer_summary_id_not_null NOT NULL,
    tenant_id integer CONSTRAINT sum_daily_explorer_tenant_id_not_null NOT NULL,
    business_date date CONSTRAINT sum_daily_explorer_business_date_not_null NOT NULL,
    merchant_id bigint,
    store_id bigint,
    terminal_id bigint,
    transaction_type character varying(50),
    card_scheme character varying(50),
    card_type character varying(50),
    destination character varying(50),
    channel character varying(50),
    txn_currency character varying(10),
    store_base_currency character varying(10),
    is_opt_in boolean,
    total_txns bigint DEFAULT 0,
    total_txn_currency_amount numeric(21,4) DEFAULT 0,
    total_base_volume numeric(21,4) DEFAULT 0,
    total_msf numeric(21,4) DEFAULT 0,
    total_vat numeric(21,4) DEFAULT 0,
    total_settled numeric(21,4) DEFAULT 0,
    total_interchange numeric(21,4) DEFAULT 0,
    total_scheme_fee numeric(21,4) DEFAULT 0
);


--
-- Name: sum_daily_explorer_y2024; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sum_daily_explorer_y2024 (
    summary_id bigint DEFAULT nextval('public.sum_daily_explorer_summary_id_seq'::regclass) CONSTRAINT sum_daily_explorer_summary_id_not_null NOT NULL,
    tenant_id integer CONSTRAINT sum_daily_explorer_tenant_id_not_null NOT NULL,
    business_date date CONSTRAINT sum_daily_explorer_business_date_not_null NOT NULL,
    merchant_id bigint,
    store_id bigint,
    terminal_id bigint,
    transaction_type character varying(50),
    card_scheme character varying(50),
    card_type character varying(50),
    destination character varying(50),
    channel character varying(50),
    txn_currency character varying(10),
    store_base_currency character varying(10),
    is_opt_in boolean,
    total_txns bigint DEFAULT 0,
    total_txn_currency_amount numeric(21,4) DEFAULT 0,
    total_base_volume numeric(21,4) DEFAULT 0,
    total_msf numeric(21,4) DEFAULT 0,
    total_vat numeric(21,4) DEFAULT 0,
    total_settled numeric(21,4) DEFAULT 0,
    total_interchange numeric(21,4) DEFAULT 0,
    total_scheme_fee numeric(21,4) DEFAULT 0
);


--
-- Name: sum_daily_explorer_y2025; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sum_daily_explorer_y2025 (
    summary_id bigint DEFAULT nextval('public.sum_daily_explorer_summary_id_seq'::regclass) CONSTRAINT sum_daily_explorer_summary_id_not_null NOT NULL,
    tenant_id integer CONSTRAINT sum_daily_explorer_tenant_id_not_null NOT NULL,
    business_date date CONSTRAINT sum_daily_explorer_business_date_not_null NOT NULL,
    merchant_id bigint,
    store_id bigint,
    terminal_id bigint,
    transaction_type character varying(50),
    card_scheme character varying(50),
    card_type character varying(50),
    destination character varying(50),
    channel character varying(50),
    txn_currency character varying(10),
    store_base_currency character varying(10),
    is_opt_in boolean,
    total_txns bigint DEFAULT 0,
    total_txn_currency_amount numeric(21,4) DEFAULT 0,
    total_base_volume numeric(21,4) DEFAULT 0,
    total_msf numeric(21,4) DEFAULT 0,
    total_vat numeric(21,4) DEFAULT 0,
    total_settled numeric(21,4) DEFAULT 0,
    total_interchange numeric(21,4) DEFAULT 0,
    total_scheme_fee numeric(21,4) DEFAULT 0
);


--
-- Name: sum_daily_explorer_y2026; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sum_daily_explorer_y2026 (
    summary_id bigint DEFAULT nextval('public.sum_daily_explorer_summary_id_seq'::regclass) CONSTRAINT sum_daily_explorer_summary_id_not_null NOT NULL,
    tenant_id integer CONSTRAINT sum_daily_explorer_tenant_id_not_null NOT NULL,
    business_date date CONSTRAINT sum_daily_explorer_business_date_not_null NOT NULL,
    merchant_id bigint,
    store_id bigint,
    terminal_id bigint,
    transaction_type character varying(50),
    card_scheme character varying(50),
    card_type character varying(50),
    destination character varying(50),
    channel character varying(50),
    txn_currency character varying(10),
    store_base_currency character varying(10),
    is_opt_in boolean,
    total_txns bigint DEFAULT 0,
    total_txn_currency_amount numeric(21,4) DEFAULT 0,
    total_base_volume numeric(21,4) DEFAULT 0,
    total_msf numeric(21,4) DEFAULT 0,
    total_vat numeric(21,4) DEFAULT 0,
    total_settled numeric(21,4) DEFAULT 0,
    total_interchange numeric(21,4) DEFAULT 0,
    total_scheme_fee numeric(21,4) DEFAULT 0
);


--
-- Name: sum_daily_explorer_y2027; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sum_daily_explorer_y2027 (
    summary_id bigint DEFAULT nextval('public.sum_daily_explorer_summary_id_seq'::regclass) CONSTRAINT sum_daily_explorer_summary_id_not_null NOT NULL,
    tenant_id integer CONSTRAINT sum_daily_explorer_tenant_id_not_null NOT NULL,
    business_date date CONSTRAINT sum_daily_explorer_business_date_not_null NOT NULL,
    merchant_id bigint,
    store_id bigint,
    terminal_id bigint,
    transaction_type character varying(50),
    card_scheme character varying(50),
    card_type character varying(50),
    destination character varying(50),
    channel character varying(50),
    txn_currency character varying(10),
    store_base_currency character varying(10),
    is_opt_in boolean,
    total_txns bigint DEFAULT 0,
    total_txn_currency_amount numeric(21,4) DEFAULT 0,
    total_base_volume numeric(21,4) DEFAULT 0,
    total_msf numeric(21,4) DEFAULT 0,
    total_vat numeric(21,4) DEFAULT 0,
    total_settled numeric(21,4) DEFAULT 0,
    total_interchange numeric(21,4) DEFAULT 0,
    total_scheme_fee numeric(21,4) DEFAULT 0
);


--
-- Name: sum_daily_finance; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sum_daily_finance (
    summary_id bigint NOT NULL,
    tenant_id integer NOT NULL,
    business_date date NOT NULL,
    dom_debit_cnt bigint DEFAULT 0,
    dom_debit_vol numeric(21,4) DEFAULT 0,
    dom_debit_msf numeric(21,4) DEFAULT 0,
    dom_debit_optin numeric(21,4) DEFAULT 0,
    dom_credit_cnt bigint DEFAULT 0,
    dom_credit_vol numeric(21,4) DEFAULT 0,
    dom_credit_msf numeric(21,4) DEFAULT 0,
    dom_credit_optin numeric(21,4) DEFAULT 0,
    int_cnt bigint DEFAULT 0,
    int_vol numeric(21,4) DEFAULT 0,
    int_msf numeric(21,4) DEFAULT 0,
    int_optin numeric(21,4) DEFAULT 0,
    total_vol numeric(21,4) DEFAULT 0,
    total_msf numeric(21,4) DEFAULT 0
)
PARTITION BY RANGE (business_date);


--
-- Name: sum_daily_finance_summary_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.sum_daily_finance_summary_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: sum_daily_finance_summary_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.sum_daily_finance_summary_id_seq OWNED BY public.sum_daily_finance.summary_id;


--
-- Name: sum_daily_finance_default; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sum_daily_finance_default (
    summary_id bigint DEFAULT nextval('public.sum_daily_finance_summary_id_seq'::regclass) CONSTRAINT sum_daily_finance_summary_id_not_null NOT NULL,
    tenant_id integer CONSTRAINT sum_daily_finance_tenant_id_not_null NOT NULL,
    business_date date CONSTRAINT sum_daily_finance_business_date_not_null NOT NULL,
    dom_debit_cnt bigint DEFAULT 0,
    dom_debit_vol numeric(21,4) DEFAULT 0,
    dom_debit_msf numeric(21,4) DEFAULT 0,
    dom_debit_optin numeric(21,4) DEFAULT 0,
    dom_credit_cnt bigint DEFAULT 0,
    dom_credit_vol numeric(21,4) DEFAULT 0,
    dom_credit_msf numeric(21,4) DEFAULT 0,
    dom_credit_optin numeric(21,4) DEFAULT 0,
    int_cnt bigint DEFAULT 0,
    int_vol numeric(21,4) DEFAULT 0,
    int_msf numeric(21,4) DEFAULT 0,
    int_optin numeric(21,4) DEFAULT 0,
    total_vol numeric(21,4) DEFAULT 0,
    total_msf numeric(21,4) DEFAULT 0
);


--
-- Name: sum_daily_finance_rollup; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sum_daily_finance_rollup (
    tenant_id integer NOT NULL,
    business_date date NOT NULL,
    dom_debit_cnt bigint DEFAULT 0 NOT NULL,
    dom_debit_vol numeric(19,2) DEFAULT 0 NOT NULL,
    dom_debit_msf numeric(21,4) DEFAULT 0 NOT NULL,
    dom_debit_optin numeric(19,2) DEFAULT 0 NOT NULL,
    dom_credit_cnt bigint DEFAULT 0 NOT NULL,
    dom_credit_vol numeric(19,2) DEFAULT 0 NOT NULL,
    dom_credit_msf numeric(21,4) DEFAULT 0 NOT NULL,
    dom_credit_optin numeric(19,2) DEFAULT 0 NOT NULL,
    int_cnt bigint DEFAULT 0 NOT NULL,
    int_vol numeric(19,2) DEFAULT 0 NOT NULL,
    int_msf numeric(21,4) DEFAULT 0 NOT NULL,
    int_optin numeric(19,2) DEFAULT 0 NOT NULL,
    total_vol numeric(19,2) DEFAULT 0 NOT NULL,
    total_msf numeric(21,4) DEFAULT 0 NOT NULL,
    dom_debit_ic numeric(21,4) DEFAULT 0 NOT NULL,
    dom_debit_sf numeric(21,4) DEFAULT 0 NOT NULL,
    dom_credit_ic numeric(21,4) DEFAULT 0 NOT NULL,
    dom_credit_sf numeric(21,4) DEFAULT 0 NOT NULL,
    int_ic numeric(21,4) DEFAULT 0 NOT NULL,
    int_sf numeric(21,4) DEFAULT 0 NOT NULL,
    total_ic numeric(21,4) DEFAULT 0 NOT NULL,
    total_sf numeric(21,4) DEFAULT 0 NOT NULL,
    fee_basis_msf numeric(21,4) DEFAULT 0 NOT NULL,
    total_pg numeric(21,4) DEFAULT 0 NOT NULL,
    pivot_built boolean DEFAULT false NOT NULL,
    fees_built boolean DEFAULT false NOT NULL,
    built_at timestamp without time zone DEFAULT now() NOT NULL,
    dcc_acquirer numeric(19,4) DEFAULT 0 NOT NULL,
    dcc_merchant numeric(19,4) DEFAULT 0 NOT NULL,
    rental_amount numeric(19,4) DEFAULT 0 NOT NULL,
    fx_revenue numeric(21,4) DEFAULT 0 NOT NULL
);


--
-- Name: sum_daily_finance_y2024; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sum_daily_finance_y2024 (
    summary_id bigint DEFAULT nextval('public.sum_daily_finance_summary_id_seq'::regclass) CONSTRAINT sum_daily_finance_summary_id_not_null NOT NULL,
    tenant_id integer CONSTRAINT sum_daily_finance_tenant_id_not_null NOT NULL,
    business_date date CONSTRAINT sum_daily_finance_business_date_not_null NOT NULL,
    dom_debit_cnt bigint DEFAULT 0,
    dom_debit_vol numeric(21,4) DEFAULT 0,
    dom_debit_msf numeric(21,4) DEFAULT 0,
    dom_debit_optin numeric(21,4) DEFAULT 0,
    dom_credit_cnt bigint DEFAULT 0,
    dom_credit_vol numeric(21,4) DEFAULT 0,
    dom_credit_msf numeric(21,4) DEFAULT 0,
    dom_credit_optin numeric(21,4) DEFAULT 0,
    int_cnt bigint DEFAULT 0,
    int_vol numeric(21,4) DEFAULT 0,
    int_msf numeric(21,4) DEFAULT 0,
    int_optin numeric(21,4) DEFAULT 0,
    total_vol numeric(21,4) DEFAULT 0,
    total_msf numeric(21,4) DEFAULT 0
);


--
-- Name: sum_daily_finance_y2025; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sum_daily_finance_y2025 (
    summary_id bigint DEFAULT nextval('public.sum_daily_finance_summary_id_seq'::regclass) CONSTRAINT sum_daily_finance_summary_id_not_null NOT NULL,
    tenant_id integer CONSTRAINT sum_daily_finance_tenant_id_not_null NOT NULL,
    business_date date CONSTRAINT sum_daily_finance_business_date_not_null NOT NULL,
    dom_debit_cnt bigint DEFAULT 0,
    dom_debit_vol numeric(21,4) DEFAULT 0,
    dom_debit_msf numeric(21,4) DEFAULT 0,
    dom_debit_optin numeric(21,4) DEFAULT 0,
    dom_credit_cnt bigint DEFAULT 0,
    dom_credit_vol numeric(21,4) DEFAULT 0,
    dom_credit_msf numeric(21,4) DEFAULT 0,
    dom_credit_optin numeric(21,4) DEFAULT 0,
    int_cnt bigint DEFAULT 0,
    int_vol numeric(21,4) DEFAULT 0,
    int_msf numeric(21,4) DEFAULT 0,
    int_optin numeric(21,4) DEFAULT 0,
    total_vol numeric(21,4) DEFAULT 0,
    total_msf numeric(21,4) DEFAULT 0
);


--
-- Name: sum_daily_finance_y2026; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sum_daily_finance_y2026 (
    summary_id bigint DEFAULT nextval('public.sum_daily_finance_summary_id_seq'::regclass) CONSTRAINT sum_daily_finance_summary_id_not_null NOT NULL,
    tenant_id integer CONSTRAINT sum_daily_finance_tenant_id_not_null NOT NULL,
    business_date date CONSTRAINT sum_daily_finance_business_date_not_null NOT NULL,
    dom_debit_cnt bigint DEFAULT 0,
    dom_debit_vol numeric(21,4) DEFAULT 0,
    dom_debit_msf numeric(21,4) DEFAULT 0,
    dom_debit_optin numeric(21,4) DEFAULT 0,
    dom_credit_cnt bigint DEFAULT 0,
    dom_credit_vol numeric(21,4) DEFAULT 0,
    dom_credit_msf numeric(21,4) DEFAULT 0,
    dom_credit_optin numeric(21,4) DEFAULT 0,
    int_cnt bigint DEFAULT 0,
    int_vol numeric(21,4) DEFAULT 0,
    int_msf numeric(21,4) DEFAULT 0,
    int_optin numeric(21,4) DEFAULT 0,
    total_vol numeric(21,4) DEFAULT 0,
    total_msf numeric(21,4) DEFAULT 0
);


--
-- Name: sum_daily_finance_y2027; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sum_daily_finance_y2027 (
    summary_id bigint DEFAULT nextval('public.sum_daily_finance_summary_id_seq'::regclass) CONSTRAINT sum_daily_finance_summary_id_not_null NOT NULL,
    tenant_id integer CONSTRAINT sum_daily_finance_tenant_id_not_null NOT NULL,
    business_date date CONSTRAINT sum_daily_finance_business_date_not_null NOT NULL,
    dom_debit_cnt bigint DEFAULT 0,
    dom_debit_vol numeric(21,4) DEFAULT 0,
    dom_debit_msf numeric(21,4) DEFAULT 0,
    dom_debit_optin numeric(21,4) DEFAULT 0,
    dom_credit_cnt bigint DEFAULT 0,
    dom_credit_vol numeric(21,4) DEFAULT 0,
    dom_credit_msf numeric(21,4) DEFAULT 0,
    dom_credit_optin numeric(21,4) DEFAULT 0,
    int_cnt bigint DEFAULT 0,
    int_vol numeric(21,4) DEFAULT 0,
    int_msf numeric(21,4) DEFAULT 0,
    int_optin numeric(21,4) DEFAULT 0,
    total_vol numeric(21,4) DEFAULT 0,
    total_msf numeric(21,4) DEFAULT 0
);


--
-- Name: sum_daily_full; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sum_daily_full (
    summary_id bigint NOT NULL,
    tenant_id integer NOT NULL,
    business_date date NOT NULL,
    merchant_id bigint,
    store_id bigint,
    mcc character varying(10),
    channel character varying(50),
    destination character varying(50),
    card_scheme character varying(50),
    card_type character varying(50),
    is_opt_in boolean,
    total_txns bigint DEFAULT 0,
    total_volume numeric(21,4) DEFAULT 0,
    total_msf numeric(21,4) DEFAULT 0,
    total_interchange numeric(21,4) DEFAULT 0,
    total_scheme_fee numeric(21,4) DEFAULT 0,
    total_ecom_fee numeric(21,4) DEFAULT 0,
    total_net_revenue numeric(21,4) DEFAULT 0,
    dcc_optin_count bigint DEFAULT 0,
    channel_class character varying(10)
)
PARTITION BY RANGE (business_date);


--
-- Name: COLUMN sum_daily_full.channel_class; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.sum_daily_full.channel_class IS 'Normalized channel (POS/ECOM) resolved from the raw terminal type in `channel` via terminal_channel_map. Functionally dependent on (tenant, channel) - not part of the grain. Read path for the executive POS/ECOM selector (ChannelSql).';


--
-- Name: sum_daily_full_summary_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.sum_daily_full_summary_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: sum_daily_full_summary_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.sum_daily_full_summary_id_seq OWNED BY public.sum_daily_full.summary_id;


--
-- Name: sum_daily_full_default; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sum_daily_full_default (
    summary_id bigint DEFAULT nextval('public.sum_daily_full_summary_id_seq'::regclass) CONSTRAINT sum_daily_full_summary_id_not_null NOT NULL,
    tenant_id integer CONSTRAINT sum_daily_full_tenant_id_not_null NOT NULL,
    business_date date CONSTRAINT sum_daily_full_business_date_not_null NOT NULL,
    merchant_id bigint,
    store_id bigint,
    mcc character varying(10),
    channel character varying(50),
    destination character varying(50),
    card_scheme character varying(50),
    card_type character varying(50),
    is_opt_in boolean,
    total_txns bigint DEFAULT 0,
    total_volume numeric(21,4) DEFAULT 0,
    total_msf numeric(21,4) DEFAULT 0,
    total_interchange numeric(21,4) DEFAULT 0,
    total_scheme_fee numeric(21,4) DEFAULT 0,
    total_ecom_fee numeric(21,4) DEFAULT 0,
    total_net_revenue numeric(21,4) DEFAULT 0,
    dcc_optin_count bigint DEFAULT 0,
    channel_class character varying(10)
);


--
-- Name: sum_daily_full_y2024; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sum_daily_full_y2024 (
    summary_id bigint DEFAULT nextval('public.sum_daily_full_summary_id_seq'::regclass) CONSTRAINT sum_daily_full_summary_id_not_null NOT NULL,
    tenant_id integer CONSTRAINT sum_daily_full_tenant_id_not_null NOT NULL,
    business_date date CONSTRAINT sum_daily_full_business_date_not_null NOT NULL,
    merchant_id bigint,
    store_id bigint,
    mcc character varying(10),
    channel character varying(50),
    destination character varying(50),
    card_scheme character varying(50),
    card_type character varying(50),
    is_opt_in boolean,
    total_txns bigint DEFAULT 0,
    total_volume numeric(21,4) DEFAULT 0,
    total_msf numeric(21,4) DEFAULT 0,
    total_interchange numeric(21,4) DEFAULT 0,
    total_scheme_fee numeric(21,4) DEFAULT 0,
    total_ecom_fee numeric(21,4) DEFAULT 0,
    total_net_revenue numeric(21,4) DEFAULT 0,
    dcc_optin_count bigint DEFAULT 0,
    channel_class character varying(10)
);


--
-- Name: sum_daily_full_y2025; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sum_daily_full_y2025 (
    summary_id bigint DEFAULT nextval('public.sum_daily_full_summary_id_seq'::regclass) CONSTRAINT sum_daily_full_summary_id_not_null NOT NULL,
    tenant_id integer CONSTRAINT sum_daily_full_tenant_id_not_null NOT NULL,
    business_date date CONSTRAINT sum_daily_full_business_date_not_null NOT NULL,
    merchant_id bigint,
    store_id bigint,
    mcc character varying(10),
    channel character varying(50),
    destination character varying(50),
    card_scheme character varying(50),
    card_type character varying(50),
    is_opt_in boolean,
    total_txns bigint DEFAULT 0,
    total_volume numeric(21,4) DEFAULT 0,
    total_msf numeric(21,4) DEFAULT 0,
    total_interchange numeric(21,4) DEFAULT 0,
    total_scheme_fee numeric(21,4) DEFAULT 0,
    total_ecom_fee numeric(21,4) DEFAULT 0,
    total_net_revenue numeric(21,4) DEFAULT 0,
    dcc_optin_count bigint DEFAULT 0,
    channel_class character varying(10)
);


--
-- Name: sum_daily_full_y2026; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sum_daily_full_y2026 (
    summary_id bigint DEFAULT nextval('public.sum_daily_full_summary_id_seq'::regclass) CONSTRAINT sum_daily_full_summary_id_not_null NOT NULL,
    tenant_id integer CONSTRAINT sum_daily_full_tenant_id_not_null NOT NULL,
    business_date date CONSTRAINT sum_daily_full_business_date_not_null NOT NULL,
    merchant_id bigint,
    store_id bigint,
    mcc character varying(10),
    channel character varying(50),
    destination character varying(50),
    card_scheme character varying(50),
    card_type character varying(50),
    is_opt_in boolean,
    total_txns bigint DEFAULT 0,
    total_volume numeric(21,4) DEFAULT 0,
    total_msf numeric(21,4) DEFAULT 0,
    total_interchange numeric(21,4) DEFAULT 0,
    total_scheme_fee numeric(21,4) DEFAULT 0,
    total_ecom_fee numeric(21,4) DEFAULT 0,
    total_net_revenue numeric(21,4) DEFAULT 0,
    dcc_optin_count bigint DEFAULT 0,
    channel_class character varying(10)
);


--
-- Name: sum_daily_full_y2027; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sum_daily_full_y2027 (
    summary_id bigint DEFAULT nextval('public.sum_daily_full_summary_id_seq'::regclass) CONSTRAINT sum_daily_full_summary_id_not_null NOT NULL,
    tenant_id integer CONSTRAINT sum_daily_full_tenant_id_not_null NOT NULL,
    business_date date CONSTRAINT sum_daily_full_business_date_not_null NOT NULL,
    merchant_id bigint,
    store_id bigint,
    mcc character varying(10),
    channel character varying(50),
    destination character varying(50),
    card_scheme character varying(50),
    card_type character varying(50),
    is_opt_in boolean,
    total_txns bigint DEFAULT 0,
    total_volume numeric(21,4) DEFAULT 0,
    total_msf numeric(21,4) DEFAULT 0,
    total_interchange numeric(21,4) DEFAULT 0,
    total_scheme_fee numeric(21,4) DEFAULT 0,
    total_ecom_fee numeric(21,4) DEFAULT 0,
    total_net_revenue numeric(21,4) DEFAULT 0,
    dcc_optin_count bigint DEFAULT 0,
    channel_class character varying(10)
);


--
-- Name: sum_daily_insight; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sum_daily_insight (
    summary_id bigint NOT NULL,
    tenant_id integer NOT NULL,
    business_date date NOT NULL,
    merchant_id bigint,
    store_id bigint,
    terminal_id bigint,
    card_scheme character varying(50),
    card_type character varying(50),
    destination character varying(50),
    channel character varying(50),
    is_opt_in boolean,
    total_txns bigint DEFAULT 0,
    total_volume numeric(21,4) DEFAULT 0,
    total_msf numeric(21,4) DEFAULT 0
)
PARTITION BY RANGE (business_date);


--
-- Name: sum_daily_insight_summary_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.sum_daily_insight_summary_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: sum_daily_insight_summary_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.sum_daily_insight_summary_id_seq OWNED BY public.sum_daily_insight.summary_id;


--
-- Name: sum_daily_insight_default; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sum_daily_insight_default (
    summary_id bigint DEFAULT nextval('public.sum_daily_insight_summary_id_seq'::regclass) CONSTRAINT sum_daily_insight_summary_id_not_null NOT NULL,
    tenant_id integer CONSTRAINT sum_daily_insight_tenant_id_not_null NOT NULL,
    business_date date CONSTRAINT sum_daily_insight_business_date_not_null NOT NULL,
    merchant_id bigint,
    store_id bigint,
    terminal_id bigint,
    card_scheme character varying(50),
    card_type character varying(50),
    destination character varying(50),
    channel character varying(50),
    is_opt_in boolean,
    total_txns bigint DEFAULT 0,
    total_volume numeric(21,4) DEFAULT 0,
    total_msf numeric(21,4) DEFAULT 0
);


--
-- Name: sum_daily_insight_y2024; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sum_daily_insight_y2024 (
    summary_id bigint DEFAULT nextval('public.sum_daily_insight_summary_id_seq'::regclass) CONSTRAINT sum_daily_insight_summary_id_not_null NOT NULL,
    tenant_id integer CONSTRAINT sum_daily_insight_tenant_id_not_null NOT NULL,
    business_date date CONSTRAINT sum_daily_insight_business_date_not_null NOT NULL,
    merchant_id bigint,
    store_id bigint,
    terminal_id bigint,
    card_scheme character varying(50),
    card_type character varying(50),
    destination character varying(50),
    channel character varying(50),
    is_opt_in boolean,
    total_txns bigint DEFAULT 0,
    total_volume numeric(21,4) DEFAULT 0,
    total_msf numeric(21,4) DEFAULT 0
);


--
-- Name: sum_daily_insight_y2025; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sum_daily_insight_y2025 (
    summary_id bigint DEFAULT nextval('public.sum_daily_insight_summary_id_seq'::regclass) CONSTRAINT sum_daily_insight_summary_id_not_null NOT NULL,
    tenant_id integer CONSTRAINT sum_daily_insight_tenant_id_not_null NOT NULL,
    business_date date CONSTRAINT sum_daily_insight_business_date_not_null NOT NULL,
    merchant_id bigint,
    store_id bigint,
    terminal_id bigint,
    card_scheme character varying(50),
    card_type character varying(50),
    destination character varying(50),
    channel character varying(50),
    is_opt_in boolean,
    total_txns bigint DEFAULT 0,
    total_volume numeric(21,4) DEFAULT 0,
    total_msf numeric(21,4) DEFAULT 0
);


--
-- Name: sum_daily_insight_y2026; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sum_daily_insight_y2026 (
    summary_id bigint DEFAULT nextval('public.sum_daily_insight_summary_id_seq'::regclass) CONSTRAINT sum_daily_insight_summary_id_not_null NOT NULL,
    tenant_id integer CONSTRAINT sum_daily_insight_tenant_id_not_null NOT NULL,
    business_date date CONSTRAINT sum_daily_insight_business_date_not_null NOT NULL,
    merchant_id bigint,
    store_id bigint,
    terminal_id bigint,
    card_scheme character varying(50),
    card_type character varying(50),
    destination character varying(50),
    channel character varying(50),
    is_opt_in boolean,
    total_txns bigint DEFAULT 0,
    total_volume numeric(21,4) DEFAULT 0,
    total_msf numeric(21,4) DEFAULT 0
);


--
-- Name: sum_daily_insight_y2027; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sum_daily_insight_y2027 (
    summary_id bigint DEFAULT nextval('public.sum_daily_insight_summary_id_seq'::regclass) CONSTRAINT sum_daily_insight_summary_id_not_null NOT NULL,
    tenant_id integer CONSTRAINT sum_daily_insight_tenant_id_not_null NOT NULL,
    business_date date CONSTRAINT sum_daily_insight_business_date_not_null NOT NULL,
    merchant_id bigint,
    store_id bigint,
    terminal_id bigint,
    card_scheme character varying(50),
    card_type character varying(50),
    destination character varying(50),
    channel character varying(50),
    is_opt_in boolean,
    total_txns bigint DEFAULT 0,
    total_volume numeric(21,4) DEFAULT 0,
    total_msf numeric(21,4) DEFAULT 0
);


--
-- Name: sum_daily_local_debit_bin; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sum_daily_local_debit_bin (
    summary_id bigint NOT NULL,
    tenant_id integer NOT NULL,
    business_date date NOT NULL,
    merchant_id bigint NOT NULL,
    bin6 character varying(6) NOT NULL,
    total_txns bigint DEFAULT 0,
    total_volume numeric(19,2) DEFAULT 0,
    total_msf numeric(19,2) DEFAULT 0
)
PARTITION BY RANGE (business_date);


--
-- Name: sum_daily_local_debit_bin_summary_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.sum_daily_local_debit_bin_summary_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: sum_daily_local_debit_bin_summary_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.sum_daily_local_debit_bin_summary_id_seq OWNED BY public.sum_daily_local_debit_bin.summary_id;


--
-- Name: sum_daily_local_debit_bin_default; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sum_daily_local_debit_bin_default (
    summary_id bigint DEFAULT nextval('public.sum_daily_local_debit_bin_summary_id_seq'::regclass) CONSTRAINT sum_daily_local_debit_bin_summary_id_not_null NOT NULL,
    tenant_id integer CONSTRAINT sum_daily_local_debit_bin_tenant_id_not_null NOT NULL,
    business_date date CONSTRAINT sum_daily_local_debit_bin_business_date_not_null NOT NULL,
    merchant_id bigint CONSTRAINT sum_daily_local_debit_bin_merchant_id_not_null NOT NULL,
    bin6 character varying(6) CONSTRAINT sum_daily_local_debit_bin_bin6_not_null NOT NULL,
    total_txns bigint DEFAULT 0,
    total_volume numeric(19,2) DEFAULT 0,
    total_msf numeric(19,2) DEFAULT 0
);


--
-- Name: sum_daily_local_debit_bin_y2024; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sum_daily_local_debit_bin_y2024 (
    summary_id bigint DEFAULT nextval('public.sum_daily_local_debit_bin_summary_id_seq'::regclass) CONSTRAINT sum_daily_local_debit_bin_summary_id_not_null NOT NULL,
    tenant_id integer CONSTRAINT sum_daily_local_debit_bin_tenant_id_not_null NOT NULL,
    business_date date CONSTRAINT sum_daily_local_debit_bin_business_date_not_null NOT NULL,
    merchant_id bigint CONSTRAINT sum_daily_local_debit_bin_merchant_id_not_null NOT NULL,
    bin6 character varying(6) CONSTRAINT sum_daily_local_debit_bin_bin6_not_null NOT NULL,
    total_txns bigint DEFAULT 0,
    total_volume numeric(19,2) DEFAULT 0,
    total_msf numeric(19,2) DEFAULT 0
);


--
-- Name: sum_daily_local_debit_bin_y2025; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sum_daily_local_debit_bin_y2025 (
    summary_id bigint DEFAULT nextval('public.sum_daily_local_debit_bin_summary_id_seq'::regclass) CONSTRAINT sum_daily_local_debit_bin_summary_id_not_null NOT NULL,
    tenant_id integer CONSTRAINT sum_daily_local_debit_bin_tenant_id_not_null NOT NULL,
    business_date date CONSTRAINT sum_daily_local_debit_bin_business_date_not_null NOT NULL,
    merchant_id bigint CONSTRAINT sum_daily_local_debit_bin_merchant_id_not_null NOT NULL,
    bin6 character varying(6) CONSTRAINT sum_daily_local_debit_bin_bin6_not_null NOT NULL,
    total_txns bigint DEFAULT 0,
    total_volume numeric(19,2) DEFAULT 0,
    total_msf numeric(19,2) DEFAULT 0
);


--
-- Name: sum_daily_local_debit_bin_y2026; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sum_daily_local_debit_bin_y2026 (
    summary_id bigint DEFAULT nextval('public.sum_daily_local_debit_bin_summary_id_seq'::regclass) CONSTRAINT sum_daily_local_debit_bin_summary_id_not_null NOT NULL,
    tenant_id integer CONSTRAINT sum_daily_local_debit_bin_tenant_id_not_null NOT NULL,
    business_date date CONSTRAINT sum_daily_local_debit_bin_business_date_not_null NOT NULL,
    merchant_id bigint CONSTRAINT sum_daily_local_debit_bin_merchant_id_not_null NOT NULL,
    bin6 character varying(6) CONSTRAINT sum_daily_local_debit_bin_bin6_not_null NOT NULL,
    total_txns bigint DEFAULT 0,
    total_volume numeric(19,2) DEFAULT 0,
    total_msf numeric(19,2) DEFAULT 0
);


--
-- Name: sum_daily_local_debit_bin_y2027; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sum_daily_local_debit_bin_y2027 (
    summary_id bigint DEFAULT nextval('public.sum_daily_local_debit_bin_summary_id_seq'::regclass) CONSTRAINT sum_daily_local_debit_bin_summary_id_not_null NOT NULL,
    tenant_id integer CONSTRAINT sum_daily_local_debit_bin_tenant_id_not_null NOT NULL,
    business_date date CONSTRAINT sum_daily_local_debit_bin_business_date_not_null NOT NULL,
    merchant_id bigint CONSTRAINT sum_daily_local_debit_bin_merchant_id_not_null NOT NULL,
    bin6 character varying(6) CONSTRAINT sum_daily_local_debit_bin_bin6_not_null NOT NULL,
    total_txns bigint DEFAULT 0,
    total_volume numeric(19,2) DEFAULT 0,
    total_msf numeric(19,2) DEFAULT 0
);


--
-- Name: sum_daily_mcc; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sum_daily_mcc (
    summary_id bigint NOT NULL,
    tenant_id integer NOT NULL,
    business_date date NOT NULL,
    mcc character varying(10),
    card_scheme character varying(50),
    total_txns bigint DEFAULT 0,
    total_volume numeric(21,4) DEFAULT 0,
    total_msf numeric(21,4) DEFAULT 0,
    total_scheme_fee numeric(21,4) DEFAULT 0,
    total_net_revenue numeric(21,4) DEFAULT 0
);


--
-- Name: sum_daily_mcc_summary_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.sum_daily_mcc_summary_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: sum_daily_mcc_summary_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.sum_daily_mcc_summary_id_seq OWNED BY public.sum_daily_mcc.summary_id;


--
-- Name: sum_daily_merchant_attribute; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sum_daily_merchant_attribute (
    id bigint NOT NULL,
    merchant_id bigint NOT NULL,
    business_date date NOT NULL,
    attribute_type character varying(50) NOT NULL,
    attribute_value character varying(100) NOT NULL,
    metric_count bigint DEFAULT 0,
    metric_volume numeric(21,4) DEFAULT 0,
    version bigint,
    tenant_id integer NOT NULL
)
PARTITION BY RANGE (business_date);


--
-- Name: sum_daily_merchant_attribute_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.sum_daily_merchant_attribute_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: sum_daily_merchant_attribute_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.sum_daily_merchant_attribute_id_seq OWNED BY public.sum_daily_merchant_attribute.id;


--
-- Name: sum_daily_merch_attr_default; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sum_daily_merch_attr_default (
    id bigint DEFAULT nextval('public.sum_daily_merchant_attribute_id_seq'::regclass) CONSTRAINT sum_daily_merchant_attribute_id_not_null NOT NULL,
    merchant_id bigint CONSTRAINT sum_daily_merchant_attribute_merchant_id_not_null NOT NULL,
    business_date date CONSTRAINT sum_daily_merchant_attribute_business_date_not_null NOT NULL,
    attribute_type character varying(50) CONSTRAINT sum_daily_merchant_attribute_attribute_type_not_null NOT NULL,
    attribute_value character varying(100) CONSTRAINT sum_daily_merchant_attribute_attribute_value_not_null NOT NULL,
    metric_count bigint DEFAULT 0,
    metric_volume numeric(21,4) DEFAULT 0,
    version bigint,
    tenant_id integer CONSTRAINT sum_daily_merchant_attribute_tenant_id_not_null NOT NULL
);


--
-- Name: sum_daily_merch_attr_y2024; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sum_daily_merch_attr_y2024 (
    id bigint DEFAULT nextval('public.sum_daily_merchant_attribute_id_seq'::regclass) CONSTRAINT sum_daily_merchant_attribute_id_not_null NOT NULL,
    merchant_id bigint CONSTRAINT sum_daily_merchant_attribute_merchant_id_not_null NOT NULL,
    business_date date CONSTRAINT sum_daily_merchant_attribute_business_date_not_null NOT NULL,
    attribute_type character varying(50) CONSTRAINT sum_daily_merchant_attribute_attribute_type_not_null NOT NULL,
    attribute_value character varying(100) CONSTRAINT sum_daily_merchant_attribute_attribute_value_not_null NOT NULL,
    metric_count bigint DEFAULT 0,
    metric_volume numeric(21,4) DEFAULT 0,
    version bigint,
    tenant_id integer CONSTRAINT sum_daily_merchant_attribute_tenant_id_not_null NOT NULL
);


--
-- Name: sum_daily_merch_attr_y2025; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sum_daily_merch_attr_y2025 (
    id bigint DEFAULT nextval('public.sum_daily_merchant_attribute_id_seq'::regclass) CONSTRAINT sum_daily_merchant_attribute_id_not_null NOT NULL,
    merchant_id bigint CONSTRAINT sum_daily_merchant_attribute_merchant_id_not_null NOT NULL,
    business_date date CONSTRAINT sum_daily_merchant_attribute_business_date_not_null NOT NULL,
    attribute_type character varying(50) CONSTRAINT sum_daily_merchant_attribute_attribute_type_not_null NOT NULL,
    attribute_value character varying(100) CONSTRAINT sum_daily_merchant_attribute_attribute_value_not_null NOT NULL,
    metric_count bigint DEFAULT 0,
    metric_volume numeric(21,4) DEFAULT 0,
    version bigint,
    tenant_id integer CONSTRAINT sum_daily_merchant_attribute_tenant_id_not_null NOT NULL
);


--
-- Name: sum_daily_merch_attr_y2026; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sum_daily_merch_attr_y2026 (
    id bigint DEFAULT nextval('public.sum_daily_merchant_attribute_id_seq'::regclass) CONSTRAINT sum_daily_merchant_attribute_id_not_null NOT NULL,
    merchant_id bigint CONSTRAINT sum_daily_merchant_attribute_merchant_id_not_null NOT NULL,
    business_date date CONSTRAINT sum_daily_merchant_attribute_business_date_not_null NOT NULL,
    attribute_type character varying(50) CONSTRAINT sum_daily_merchant_attribute_attribute_type_not_null NOT NULL,
    attribute_value character varying(100) CONSTRAINT sum_daily_merchant_attribute_attribute_value_not_null NOT NULL,
    metric_count bigint DEFAULT 0,
    metric_volume numeric(21,4) DEFAULT 0,
    version bigint,
    tenant_id integer CONSTRAINT sum_daily_merchant_attribute_tenant_id_not_null NOT NULL
);


--
-- Name: sum_daily_merchant; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sum_daily_merchant (
    summary_id bigint NOT NULL,
    tenant_id integer NOT NULL,
    business_date date NOT NULL,
    institution_id integer,
    merchant_id bigint,
    store_id bigint,
    total_txns integer,
    total_volume numeric(21,4),
    total_msf numeric(21,4),
    total_interchange numeric(21,4),
    total_scheme_fee numeric(21,4),
    total_margin numeric(21,4),
    total_debit_prepaid_volume numeric(21,4) DEFAULT 0,
    total_credit_volume numeric(21,4) DEFAULT 0,
    sales_user_id character varying(50),
    unique_customer_count bigint DEFAULT 0,
    top_spending_customer_id character varying(50),
    top_spending_amount numeric(21,4),
    total_base_volume numeric(21,4) DEFAULT 0,
    dcc_eligible_volume numeric(21,4) DEFAULT 0,
    dcc_optin_volume numeric(21,4) DEFAULT 0,
    dcc_optout_volume numeric(21,4) DEFAULT 0,
    dcc_eligible_count bigint DEFAULT 0,
    dcc_optin_count bigint DEFAULT 0,
    total_ecom_fee numeric(21,4) DEFAULT 0,
    dcc_acquirer numeric(19,4) DEFAULT 0 NOT NULL,
    dcc_merchant numeric(19,4) DEFAULT 0 NOT NULL,
    rental_amount numeric(19,4) DEFAULT 0 NOT NULL,
    fx_revenue numeric(21,4) DEFAULT 0 NOT NULL
)
PARTITION BY RANGE (business_date);


--
-- Name: COLUMN sum_daily_merchant.fx_revenue; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.sum_daily_merchant.fx_revenue IS 'ECOM FX income (excl. Benefit PG), re-derived from fact_transaction x ref_ecom_fx_rate by AncillarySql. Added to Net Spread only when tenant_setting netspread.fx_enabled = true.';


--
-- Name: sum_daily_merchant_summary_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.sum_daily_merchant_summary_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: sum_daily_merchant_summary_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.sum_daily_merchant_summary_id_seq OWNED BY public.sum_daily_merchant.summary_id;


--
-- Name: sum_daily_merchant_default; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sum_daily_merchant_default (
    summary_id bigint DEFAULT nextval('public.sum_daily_merchant_summary_id_seq'::regclass) CONSTRAINT sum_daily_merchant_summary_id_not_null NOT NULL,
    tenant_id integer CONSTRAINT sum_daily_merchant_tenant_id_not_null NOT NULL,
    business_date date CONSTRAINT sum_daily_merchant_business_date_not_null NOT NULL,
    institution_id integer,
    merchant_id bigint,
    store_id bigint,
    total_txns integer,
    total_volume numeric(21,4),
    total_msf numeric(21,4),
    total_interchange numeric(21,4),
    total_scheme_fee numeric(21,4),
    total_margin numeric(21,4),
    total_debit_prepaid_volume numeric(21,4) DEFAULT 0,
    total_credit_volume numeric(21,4) DEFAULT 0,
    sales_user_id character varying(50),
    unique_customer_count bigint DEFAULT 0,
    top_spending_customer_id character varying(50),
    top_spending_amount numeric(21,4),
    total_base_volume numeric(21,4) DEFAULT 0,
    dcc_eligible_volume numeric(21,4) DEFAULT 0,
    dcc_optin_volume numeric(21,4) DEFAULT 0,
    dcc_optout_volume numeric(21,4) DEFAULT 0,
    dcc_eligible_count bigint DEFAULT 0,
    dcc_optin_count bigint DEFAULT 0,
    total_ecom_fee numeric(21,4) DEFAULT 0,
    dcc_acquirer numeric(19,4) DEFAULT 0 CONSTRAINT sum_daily_merchant_dcc_acquirer_not_null NOT NULL,
    dcc_merchant numeric(19,4) DEFAULT 0 CONSTRAINT sum_daily_merchant_dcc_merchant_not_null NOT NULL,
    rental_amount numeric(19,4) DEFAULT 0 CONSTRAINT sum_daily_merchant_rental_amount_not_null NOT NULL,
    fx_revenue numeric(21,4) DEFAULT 0 CONSTRAINT sum_daily_merchant_fx_revenue_not_null NOT NULL
);


--
-- Name: sum_daily_merchant_destination; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sum_daily_merchant_destination (
    summary_id bigint NOT NULL,
    tenant_id integer NOT NULL,
    business_date date NOT NULL,
    merchant_id bigint,
    destination character varying(20) NOT NULL,
    total_txns bigint DEFAULT 0,
    total_volume numeric(21,4) DEFAULT 0,
    total_msf numeric(21,4) DEFAULT 0,
    total_interchange numeric(21,4) DEFAULT 0,
    total_scheme_fee numeric(21,4) DEFAULT 0,
    total_ecom_fee numeric(21,4) DEFAULT 0,
    total_net_revenue numeric(21,4) DEFAULT 0
)
PARTITION BY RANGE (business_date);


--
-- Name: sum_daily_merchant_destination_summary_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.sum_daily_merchant_destination_summary_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: sum_daily_merchant_destination_summary_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.sum_daily_merchant_destination_summary_id_seq OWNED BY public.sum_daily_merchant_destination.summary_id;


--
-- Name: sum_daily_merchant_destination_default; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sum_daily_merchant_destination_default (
    summary_id bigint DEFAULT nextval('public.sum_daily_merchant_destination_summary_id_seq'::regclass) CONSTRAINT sum_daily_merchant_destination_summary_id_not_null NOT NULL,
    tenant_id integer CONSTRAINT sum_daily_merchant_destination_tenant_id_not_null NOT NULL,
    business_date date CONSTRAINT sum_daily_merchant_destination_business_date_not_null NOT NULL,
    merchant_id bigint,
    destination character varying(20) CONSTRAINT sum_daily_merchant_destination_destination_not_null NOT NULL,
    total_txns bigint DEFAULT 0,
    total_volume numeric(21,4) DEFAULT 0,
    total_msf numeric(21,4) DEFAULT 0,
    total_interchange numeric(21,4) DEFAULT 0,
    total_scheme_fee numeric(21,4) DEFAULT 0,
    total_ecom_fee numeric(21,4) DEFAULT 0,
    total_net_revenue numeric(21,4) DEFAULT 0
);


--
-- Name: sum_daily_merchant_destination_y2024; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sum_daily_merchant_destination_y2024 (
    summary_id bigint DEFAULT nextval('public.sum_daily_merchant_destination_summary_id_seq'::regclass) CONSTRAINT sum_daily_merchant_destination_summary_id_not_null NOT NULL,
    tenant_id integer CONSTRAINT sum_daily_merchant_destination_tenant_id_not_null NOT NULL,
    business_date date CONSTRAINT sum_daily_merchant_destination_business_date_not_null NOT NULL,
    merchant_id bigint,
    destination character varying(20) CONSTRAINT sum_daily_merchant_destination_destination_not_null NOT NULL,
    total_txns bigint DEFAULT 0,
    total_volume numeric(21,4) DEFAULT 0,
    total_msf numeric(21,4) DEFAULT 0,
    total_interchange numeric(21,4) DEFAULT 0,
    total_scheme_fee numeric(21,4) DEFAULT 0,
    total_ecom_fee numeric(21,4) DEFAULT 0,
    total_net_revenue numeric(21,4) DEFAULT 0
);


--
-- Name: sum_daily_merchant_destination_y2025; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sum_daily_merchant_destination_y2025 (
    summary_id bigint DEFAULT nextval('public.sum_daily_merchant_destination_summary_id_seq'::regclass) CONSTRAINT sum_daily_merchant_destination_summary_id_not_null NOT NULL,
    tenant_id integer CONSTRAINT sum_daily_merchant_destination_tenant_id_not_null NOT NULL,
    business_date date CONSTRAINT sum_daily_merchant_destination_business_date_not_null NOT NULL,
    merchant_id bigint,
    destination character varying(20) CONSTRAINT sum_daily_merchant_destination_destination_not_null NOT NULL,
    total_txns bigint DEFAULT 0,
    total_volume numeric(21,4) DEFAULT 0,
    total_msf numeric(21,4) DEFAULT 0,
    total_interchange numeric(21,4) DEFAULT 0,
    total_scheme_fee numeric(21,4) DEFAULT 0,
    total_ecom_fee numeric(21,4) DEFAULT 0,
    total_net_revenue numeric(21,4) DEFAULT 0
);


--
-- Name: sum_daily_merchant_destination_y2026; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sum_daily_merchant_destination_y2026 (
    summary_id bigint DEFAULT nextval('public.sum_daily_merchant_destination_summary_id_seq'::regclass) CONSTRAINT sum_daily_merchant_destination_summary_id_not_null NOT NULL,
    tenant_id integer CONSTRAINT sum_daily_merchant_destination_tenant_id_not_null NOT NULL,
    business_date date CONSTRAINT sum_daily_merchant_destination_business_date_not_null NOT NULL,
    merchant_id bigint,
    destination character varying(20) CONSTRAINT sum_daily_merchant_destination_destination_not_null NOT NULL,
    total_txns bigint DEFAULT 0,
    total_volume numeric(21,4) DEFAULT 0,
    total_msf numeric(21,4) DEFAULT 0,
    total_interchange numeric(21,4) DEFAULT 0,
    total_scheme_fee numeric(21,4) DEFAULT 0,
    total_ecom_fee numeric(21,4) DEFAULT 0,
    total_net_revenue numeric(21,4) DEFAULT 0
);


--
-- Name: sum_daily_merchant_destination_y2027; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sum_daily_merchant_destination_y2027 (
    summary_id bigint DEFAULT nextval('public.sum_daily_merchant_destination_summary_id_seq'::regclass) CONSTRAINT sum_daily_merchant_destination_summary_id_not_null NOT NULL,
    tenant_id integer CONSTRAINT sum_daily_merchant_destination_tenant_id_not_null NOT NULL,
    business_date date CONSTRAINT sum_daily_merchant_destination_business_date_not_null NOT NULL,
    merchant_id bigint,
    destination character varying(20) CONSTRAINT sum_daily_merchant_destination_destination_not_null NOT NULL,
    total_txns bigint DEFAULT 0,
    total_volume numeric(21,4) DEFAULT 0,
    total_msf numeric(21,4) DEFAULT 0,
    total_interchange numeric(21,4) DEFAULT 0,
    total_scheme_fee numeric(21,4) DEFAULT 0,
    total_ecom_fee numeric(21,4) DEFAULT 0,
    total_net_revenue numeric(21,4) DEFAULT 0
);


--
-- Name: sum_daily_merchant_y2024; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sum_daily_merchant_y2024 (
    summary_id bigint DEFAULT nextval('public.sum_daily_merchant_summary_id_seq'::regclass) CONSTRAINT sum_daily_merchant_summary_id_not_null NOT NULL,
    tenant_id integer CONSTRAINT sum_daily_merchant_tenant_id_not_null NOT NULL,
    business_date date CONSTRAINT sum_daily_merchant_business_date_not_null NOT NULL,
    institution_id integer,
    merchant_id bigint,
    store_id bigint,
    total_txns integer,
    total_volume numeric(21,4),
    total_msf numeric(21,4),
    total_interchange numeric(21,4),
    total_scheme_fee numeric(21,4),
    total_margin numeric(21,4),
    total_debit_prepaid_volume numeric(21,4) DEFAULT 0,
    total_credit_volume numeric(21,4) DEFAULT 0,
    sales_user_id character varying(50),
    unique_customer_count bigint DEFAULT 0,
    top_spending_customer_id character varying(50),
    top_spending_amount numeric(21,4),
    total_base_volume numeric(21,4) DEFAULT 0,
    dcc_eligible_volume numeric(21,4) DEFAULT 0,
    dcc_optin_volume numeric(21,4) DEFAULT 0,
    dcc_optout_volume numeric(21,4) DEFAULT 0,
    dcc_eligible_count bigint DEFAULT 0,
    dcc_optin_count bigint DEFAULT 0,
    total_ecom_fee numeric(21,4) DEFAULT 0,
    dcc_acquirer numeric(19,4) DEFAULT 0 CONSTRAINT sum_daily_merchant_dcc_acquirer_not_null NOT NULL,
    dcc_merchant numeric(19,4) DEFAULT 0 CONSTRAINT sum_daily_merchant_dcc_merchant_not_null NOT NULL,
    rental_amount numeric(19,4) DEFAULT 0 CONSTRAINT sum_daily_merchant_rental_amount_not_null NOT NULL,
    fx_revenue numeric(21,4) DEFAULT 0 CONSTRAINT sum_daily_merchant_fx_revenue_not_null NOT NULL
);


--
-- Name: sum_daily_merchant_y2025; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sum_daily_merchant_y2025 (
    summary_id bigint DEFAULT nextval('public.sum_daily_merchant_summary_id_seq'::regclass) CONSTRAINT sum_daily_merchant_summary_id_not_null NOT NULL,
    tenant_id integer CONSTRAINT sum_daily_merchant_tenant_id_not_null NOT NULL,
    business_date date CONSTRAINT sum_daily_merchant_business_date_not_null NOT NULL,
    institution_id integer,
    merchant_id bigint,
    store_id bigint,
    total_txns integer,
    total_volume numeric(21,4),
    total_msf numeric(21,4),
    total_interchange numeric(21,4),
    total_scheme_fee numeric(21,4),
    total_margin numeric(21,4),
    total_debit_prepaid_volume numeric(21,4) DEFAULT 0,
    total_credit_volume numeric(21,4) DEFAULT 0,
    sales_user_id character varying(50),
    unique_customer_count bigint DEFAULT 0,
    top_spending_customer_id character varying(50),
    top_spending_amount numeric(21,4),
    total_base_volume numeric(21,4) DEFAULT 0,
    dcc_eligible_volume numeric(21,4) DEFAULT 0,
    dcc_optin_volume numeric(21,4) DEFAULT 0,
    dcc_optout_volume numeric(21,4) DEFAULT 0,
    dcc_eligible_count bigint DEFAULT 0,
    dcc_optin_count bigint DEFAULT 0,
    total_ecom_fee numeric(21,4) DEFAULT 0,
    dcc_acquirer numeric(19,4) DEFAULT 0 CONSTRAINT sum_daily_merchant_dcc_acquirer_not_null NOT NULL,
    dcc_merchant numeric(19,4) DEFAULT 0 CONSTRAINT sum_daily_merchant_dcc_merchant_not_null NOT NULL,
    rental_amount numeric(19,4) DEFAULT 0 CONSTRAINT sum_daily_merchant_rental_amount_not_null NOT NULL,
    fx_revenue numeric(21,4) DEFAULT 0 CONSTRAINT sum_daily_merchant_fx_revenue_not_null NOT NULL
);


--
-- Name: sum_daily_merchant_y2026; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sum_daily_merchant_y2026 (
    summary_id bigint DEFAULT nextval('public.sum_daily_merchant_summary_id_seq'::regclass) CONSTRAINT sum_daily_merchant_summary_id_not_null NOT NULL,
    tenant_id integer CONSTRAINT sum_daily_merchant_tenant_id_not_null NOT NULL,
    business_date date CONSTRAINT sum_daily_merchant_business_date_not_null NOT NULL,
    institution_id integer,
    merchant_id bigint,
    store_id bigint,
    total_txns integer,
    total_volume numeric(21,4),
    total_msf numeric(21,4),
    total_interchange numeric(21,4),
    total_scheme_fee numeric(21,4),
    total_margin numeric(21,4),
    total_debit_prepaid_volume numeric(21,4) DEFAULT 0,
    total_credit_volume numeric(21,4) DEFAULT 0,
    sales_user_id character varying(50),
    unique_customer_count bigint DEFAULT 0,
    top_spending_customer_id character varying(50),
    top_spending_amount numeric(21,4),
    total_base_volume numeric(21,4) DEFAULT 0,
    dcc_eligible_volume numeric(21,4) DEFAULT 0,
    dcc_optin_volume numeric(21,4) DEFAULT 0,
    dcc_optout_volume numeric(21,4) DEFAULT 0,
    dcc_eligible_count bigint DEFAULT 0,
    dcc_optin_count bigint DEFAULT 0,
    total_ecom_fee numeric(21,4) DEFAULT 0,
    dcc_acquirer numeric(19,4) DEFAULT 0 CONSTRAINT sum_daily_merchant_dcc_acquirer_not_null NOT NULL,
    dcc_merchant numeric(19,4) DEFAULT 0 CONSTRAINT sum_daily_merchant_dcc_merchant_not_null NOT NULL,
    rental_amount numeric(19,4) DEFAULT 0 CONSTRAINT sum_daily_merchant_rental_amount_not_null NOT NULL,
    fx_revenue numeric(21,4) DEFAULT 0 CONSTRAINT sum_daily_merchant_fx_revenue_not_null NOT NULL
);


--
-- Name: sum_daily_scheme; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sum_daily_scheme (
    summary_id bigint NOT NULL,
    tenant_id integer NOT NULL,
    business_date date NOT NULL,
    card_scheme character varying(50),
    total_txns bigint DEFAULT 0,
    total_volume numeric(21,4) DEFAULT 0,
    total_msf numeric(21,4) DEFAULT 0,
    total_interchange numeric(21,4) DEFAULT 0,
    total_scheme_fee numeric(21,4) DEFAULT 0,
    total_net_revenue numeric(21,4) DEFAULT 0
)
PARTITION BY RANGE (business_date);


--
-- Name: sum_daily_scheme_summary_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.sum_daily_scheme_summary_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: sum_daily_scheme_summary_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.sum_daily_scheme_summary_id_seq OWNED BY public.sum_daily_scheme.summary_id;


--
-- Name: sum_daily_scheme_default; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sum_daily_scheme_default (
    summary_id bigint DEFAULT nextval('public.sum_daily_scheme_summary_id_seq'::regclass) CONSTRAINT sum_daily_scheme_summary_id_not_null NOT NULL,
    tenant_id integer CONSTRAINT sum_daily_scheme_tenant_id_not_null NOT NULL,
    business_date date CONSTRAINT sum_daily_scheme_business_date_not_null NOT NULL,
    card_scheme character varying(50),
    total_txns bigint DEFAULT 0,
    total_volume numeric(21,4) DEFAULT 0,
    total_msf numeric(21,4) DEFAULT 0,
    total_interchange numeric(21,4) DEFAULT 0,
    total_scheme_fee numeric(21,4) DEFAULT 0,
    total_net_revenue numeric(21,4) DEFAULT 0
);


--
-- Name: sum_daily_scheme_y2024; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sum_daily_scheme_y2024 (
    summary_id bigint DEFAULT nextval('public.sum_daily_scheme_summary_id_seq'::regclass) CONSTRAINT sum_daily_scheme_summary_id_not_null NOT NULL,
    tenant_id integer CONSTRAINT sum_daily_scheme_tenant_id_not_null NOT NULL,
    business_date date CONSTRAINT sum_daily_scheme_business_date_not_null NOT NULL,
    card_scheme character varying(50),
    total_txns bigint DEFAULT 0,
    total_volume numeric(21,4) DEFAULT 0,
    total_msf numeric(21,4) DEFAULT 0,
    total_interchange numeric(21,4) DEFAULT 0,
    total_scheme_fee numeric(21,4) DEFAULT 0,
    total_net_revenue numeric(21,4) DEFAULT 0
);


--
-- Name: sum_daily_scheme_y2025; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sum_daily_scheme_y2025 (
    summary_id bigint DEFAULT nextval('public.sum_daily_scheme_summary_id_seq'::regclass) CONSTRAINT sum_daily_scheme_summary_id_not_null NOT NULL,
    tenant_id integer CONSTRAINT sum_daily_scheme_tenant_id_not_null NOT NULL,
    business_date date CONSTRAINT sum_daily_scheme_business_date_not_null NOT NULL,
    card_scheme character varying(50),
    total_txns bigint DEFAULT 0,
    total_volume numeric(21,4) DEFAULT 0,
    total_msf numeric(21,4) DEFAULT 0,
    total_interchange numeric(21,4) DEFAULT 0,
    total_scheme_fee numeric(21,4) DEFAULT 0,
    total_net_revenue numeric(21,4) DEFAULT 0
);


--
-- Name: sum_daily_scheme_y2026; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sum_daily_scheme_y2026 (
    summary_id bigint DEFAULT nextval('public.sum_daily_scheme_summary_id_seq'::regclass) CONSTRAINT sum_daily_scheme_summary_id_not_null NOT NULL,
    tenant_id integer CONSTRAINT sum_daily_scheme_tenant_id_not_null NOT NULL,
    business_date date CONSTRAINT sum_daily_scheme_business_date_not_null NOT NULL,
    card_scheme character varying(50),
    total_txns bigint DEFAULT 0,
    total_volume numeric(21,4) DEFAULT 0,
    total_msf numeric(21,4) DEFAULT 0,
    total_interchange numeric(21,4) DEFAULT 0,
    total_scheme_fee numeric(21,4) DEFAULT 0,
    total_net_revenue numeric(21,4) DEFAULT 0
);


--
-- Name: sum_daily_scheme_y2027; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sum_daily_scheme_y2027 (
    summary_id bigint DEFAULT nextval('public.sum_daily_scheme_summary_id_seq'::regclass) CONSTRAINT sum_daily_scheme_summary_id_not_null NOT NULL,
    tenant_id integer CONSTRAINT sum_daily_scheme_tenant_id_not_null NOT NULL,
    business_date date CONSTRAINT sum_daily_scheme_business_date_not_null NOT NULL,
    card_scheme character varying(50),
    total_txns bigint DEFAULT 0,
    total_volume numeric(21,4) DEFAULT 0,
    total_msf numeric(21,4) DEFAULT 0,
    total_interchange numeric(21,4) DEFAULT 0,
    total_scheme_fee numeric(21,4) DEFAULT 0,
    total_net_revenue numeric(21,4) DEFAULT 0
);


--
-- Name: sum_daily_terminal; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sum_daily_terminal (
    summary_id bigint NOT NULL,
    tenant_id integer NOT NULL,
    business_date date NOT NULL,
    merchant_id bigint,
    store_id bigint,
    terminal_id bigint,
    total_txns bigint DEFAULT 0,
    total_volume numeric(21,4) DEFAULT 0,
    total_msf numeric(21,4) DEFAULT 0,
    total_revenue numeric(21,4) DEFAULT 0,
    total_ecom_fee numeric(21,4) DEFAULT 0,
    total_base_volume numeric(21,4) DEFAULT 0,
    total_interchange numeric(21,4) DEFAULT 0,
    total_scheme_fee numeric(21,4) DEFAULT 0
)
PARTITION BY RANGE (business_date);


--
-- Name: sum_daily_terminal_summary_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.sum_daily_terminal_summary_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: sum_daily_terminal_summary_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.sum_daily_terminal_summary_id_seq OWNED BY public.sum_daily_terminal.summary_id;


--
-- Name: sum_daily_terminal_default; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sum_daily_terminal_default (
    summary_id bigint DEFAULT nextval('public.sum_daily_terminal_summary_id_seq'::regclass) CONSTRAINT sum_daily_terminal_summary_id_not_null NOT NULL,
    tenant_id integer CONSTRAINT sum_daily_terminal_tenant_id_not_null NOT NULL,
    business_date date CONSTRAINT sum_daily_terminal_business_date_not_null NOT NULL,
    merchant_id bigint,
    store_id bigint,
    terminal_id bigint,
    total_txns bigint DEFAULT 0,
    total_volume numeric(21,4) DEFAULT 0,
    total_msf numeric(21,4) DEFAULT 0,
    total_revenue numeric(21,4) DEFAULT 0,
    total_ecom_fee numeric(21,4) DEFAULT 0,
    total_base_volume numeric(21,4) DEFAULT 0,
    total_interchange numeric(21,4) DEFAULT 0,
    total_scheme_fee numeric(21,4) DEFAULT 0
);


--
-- Name: sum_daily_terminal_y2024; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sum_daily_terminal_y2024 (
    summary_id bigint DEFAULT nextval('public.sum_daily_terminal_summary_id_seq'::regclass) CONSTRAINT sum_daily_terminal_summary_id_not_null NOT NULL,
    tenant_id integer CONSTRAINT sum_daily_terminal_tenant_id_not_null NOT NULL,
    business_date date CONSTRAINT sum_daily_terminal_business_date_not_null NOT NULL,
    merchant_id bigint,
    store_id bigint,
    terminal_id bigint,
    total_txns bigint DEFAULT 0,
    total_volume numeric(21,4) DEFAULT 0,
    total_msf numeric(21,4) DEFAULT 0,
    total_revenue numeric(21,4) DEFAULT 0,
    total_ecom_fee numeric(21,4) DEFAULT 0,
    total_base_volume numeric(21,4) DEFAULT 0,
    total_interchange numeric(21,4) DEFAULT 0,
    total_scheme_fee numeric(21,4) DEFAULT 0
);


--
-- Name: sum_daily_terminal_y2025; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sum_daily_terminal_y2025 (
    summary_id bigint DEFAULT nextval('public.sum_daily_terminal_summary_id_seq'::regclass) CONSTRAINT sum_daily_terminal_summary_id_not_null NOT NULL,
    tenant_id integer CONSTRAINT sum_daily_terminal_tenant_id_not_null NOT NULL,
    business_date date CONSTRAINT sum_daily_terminal_business_date_not_null NOT NULL,
    merchant_id bigint,
    store_id bigint,
    terminal_id bigint,
    total_txns bigint DEFAULT 0,
    total_volume numeric(21,4) DEFAULT 0,
    total_msf numeric(21,4) DEFAULT 0,
    total_revenue numeric(21,4) DEFAULT 0,
    total_ecom_fee numeric(21,4) DEFAULT 0,
    total_base_volume numeric(21,4) DEFAULT 0,
    total_interchange numeric(21,4) DEFAULT 0,
    total_scheme_fee numeric(21,4) DEFAULT 0
);


--
-- Name: sum_daily_terminal_y2026; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sum_daily_terminal_y2026 (
    summary_id bigint DEFAULT nextval('public.sum_daily_terminal_summary_id_seq'::regclass) CONSTRAINT sum_daily_terminal_summary_id_not_null NOT NULL,
    tenant_id integer CONSTRAINT sum_daily_terminal_tenant_id_not_null NOT NULL,
    business_date date CONSTRAINT sum_daily_terminal_business_date_not_null NOT NULL,
    merchant_id bigint,
    store_id bigint,
    terminal_id bigint,
    total_txns bigint DEFAULT 0,
    total_volume numeric(21,4) DEFAULT 0,
    total_msf numeric(21,4) DEFAULT 0,
    total_revenue numeric(21,4) DEFAULT 0,
    total_ecom_fee numeric(21,4) DEFAULT 0,
    total_base_volume numeric(21,4) DEFAULT 0,
    total_interchange numeric(21,4) DEFAULT 0,
    total_scheme_fee numeric(21,4) DEFAULT 0
);


--
-- Name: sum_daily_terminal_y2027; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sum_daily_terminal_y2027 (
    summary_id bigint DEFAULT nextval('public.sum_daily_terminal_summary_id_seq'::regclass) CONSTRAINT sum_daily_terminal_summary_id_not_null NOT NULL,
    tenant_id integer CONSTRAINT sum_daily_terminal_tenant_id_not_null NOT NULL,
    business_date date CONSTRAINT sum_daily_terminal_business_date_not_null NOT NULL,
    merchant_id bigint,
    store_id bigint,
    terminal_id bigint,
    total_txns bigint DEFAULT 0,
    total_volume numeric(21,4) DEFAULT 0,
    total_msf numeric(21,4) DEFAULT 0,
    total_revenue numeric(21,4) DEFAULT 0,
    total_ecom_fee numeric(21,4) DEFAULT 0,
    total_base_volume numeric(21,4) DEFAULT 0,
    total_interchange numeric(21,4) DEFAULT 0,
    total_scheme_fee numeric(21,4) DEFAULT 0
);


--
-- Name: sum_monthly_bank; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sum_monthly_bank (
    summary_id bigint NOT NULL,
    tenant_id integer NOT NULL,
    month_key integer NOT NULL,
    total_txns bigint,
    total_volume numeric(21,4),
    total_msf numeric(21,4),
    total_interchange numeric(21,4),
    total_scheme_fee numeric(21,4) DEFAULT 0,
    total_vat numeric(21,4),
    total_net_revenue numeric(21,4),
    total_ecom_fee numeric(21,4) DEFAULT 0,
    total_base_volume numeric(21,4) DEFAULT 0
);


--
-- Name: sum_monthly_bank_summary_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.sum_monthly_bank_summary_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: sum_monthly_bank_summary_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.sum_monthly_bank_summary_id_seq OWNED BY public.sum_monthly_bank.summary_id;


--
-- Name: sum_monthly_card; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sum_monthly_card (
    id bigint NOT NULL,
    tenant_id bigint NOT NULL,
    merchant_id bigint,
    month_key integer,
    card_number character varying(50),
    visit_count integer DEFAULT 0,
    total_spend numeric(21,4) DEFAULT 0
);


--
-- Name: sum_monthly_card_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.sum_monthly_card_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: sum_monthly_card_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.sum_monthly_card_id_seq OWNED BY public.sum_monthly_card.id;


--
-- Name: sum_monthly_insight; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sum_monthly_insight (
    summary_id bigint NOT NULL,
    tenant_id integer NOT NULL,
    month_key integer NOT NULL,
    merchant_id bigint,
    store_id bigint,
    terminal_id bigint,
    card_scheme character varying(50),
    card_type character varying(50),
    destination character varying(50),
    channel character varying(50),
    is_opt_in boolean,
    total_txns bigint DEFAULT 0,
    total_volume numeric(21,4) DEFAULT 0,
    total_msf numeric(21,4) DEFAULT 0
);


--
-- Name: sum_monthly_insight_summary_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.sum_monthly_insight_summary_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: sum_monthly_insight_summary_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.sum_monthly_insight_summary_id_seq OWNED BY public.sum_monthly_insight.summary_id;


--
-- Name: sum_monthly_merchant_metrics; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sum_monthly_merchant_metrics (
    metric_id bigint NOT NULL,
    tenant_id bigint NOT NULL,
    merchant_id bigint,
    month_year character varying(7) NOT NULL,
    volatility_index numeric(19,4),
    stability_label character varying(50),
    behavior_tag character varying(50),
    smart_comment text,
    week_1_health character varying(20),
    week_2_health character varying(20),
    week_3_health character varying(20),
    week_4_health character varying(20),
    week_5_health character varying(20),
    total_volume numeric(21,4),
    avg_daily_volume numeric(21,4),
    max_daily_volume numeric(21,4),
    min_daily_volume numeric(21,4),
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP
);


--
-- Name: sum_monthly_merchant_metrics_metric_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.sum_monthly_merchant_metrics_metric_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: sum_monthly_merchant_metrics_metric_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.sum_monthly_merchant_metrics_metric_id_seq OWNED BY public.sum_monthly_merchant_metrics.metric_id;


--
-- Name: sys_group_menu; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sys_group_menu (
    group_id bigint NOT NULL,
    menu_id bigint NOT NULL
);


--
-- Name: sys_menu; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sys_menu (
    menu_id bigint NOT NULL,
    menu_name character varying(50) NOT NULL,
    path character varying(100),
    icon_key character varying(50),
    category character varying(50),
    display_order integer,
    menu_key character varying(60),
    module_key character varying(40),
    parent_id bigint,
    is_active boolean DEFAULT true NOT NULL
);


--
-- Name: sys_menu_menu_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.sys_menu_menu_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: sys_menu_menu_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.sys_menu_menu_id_seq OWNED BY public.sys_menu.menu_id;


--
-- Name: sys_permission; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sys_permission (
    perm_code character varying(80) NOT NULL,
    menu_key character varying(60) NOT NULL,
    action character varying(10) NOT NULL,
    description character varying(255),
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT chk_sys_permission_action CHECK (((action)::text = ANY ((ARRAY['VIEW'::character varying, 'EDIT'::character varying, 'APPROVE'::character varying, 'EXPORT'::character varying])::text[])))
);


--
-- Name: sys_user_group; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sys_user_group (
    group_id bigint NOT NULL,
    group_name character varying(50) NOT NULL,
    description character varying(255),
    is_system boolean DEFAULT false NOT NULL,
    owner_tenant_id integer,
    created_by character varying(100),
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP
);


--
-- Name: sys_user_group_group_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.sys_user_group_group_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: sys_user_group_group_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.sys_user_group_group_id_seq OWNED BY public.sys_user_group.group_id;


--
-- Name: tenant; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.tenant (
    tenant_id integer NOT NULL,
    institution_id character varying(50) NOT NULL,
    bank_name character varying(100) NOT NULL,
    bank_short_code character varying(10) NOT NULL,
    base_currency character varying(10) DEFAULT 'USD'::character varying,
    country character varying(100),
    currency_name character varying(50),
    currency_symbol character varying(10),
    region_id integer,
    status character varying(20) DEFAULT 'ACTIVE'::character varying,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    home_country_code character varying(2) DEFAULT 'AE'::character varying NOT NULL,
    input_format character varying(10) DEFAULT 'CMM'::character varying NOT NULL,
    card_type_source character varying(10) DEFAULT 'FILE'::character varying NOT NULL,
    cmm_minor_divisor integer,
    CONSTRAINT chk_tenant_card_type_source CHECK (((card_type_source)::text = ANY ((ARRAY['FILE'::character varying, 'BIN'::character varying])::text[]))),
    CONSTRAINT chk_tenant_cmm_minor_divisor CHECK (((cmm_minor_divisor IS NULL) OR (cmm_minor_divisor = ANY (ARRAY[1, 10, 100, 1000, 10000])))),
    CONSTRAINT chk_tenant_input_format CHECK (((input_format)::text = ANY ((ARRAY['CMM'::character varying, 'AMS'::character varying])::text[])))
);


--
-- Name: tenant_group; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.tenant_group (
    tenant_group_id bigint NOT NULL,
    tenant_id integer NOT NULL,
    group_id bigint NOT NULL,
    is_system boolean DEFAULT false NOT NULL,
    cloned_from bigint,
    description character varying(255),
    created_by character varying(100),
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by character varying(100),
    updated_at timestamp without time zone
);


--
-- Name: tenant_group_perm; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.tenant_group_perm (
    tenant_id integer NOT NULL,
    group_id bigint NOT NULL,
    perm_code character varying(80) NOT NULL,
    effect character varying(5) DEFAULT 'ALLOW'::character varying NOT NULL,
    granted_by character varying(100),
    granted_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT chk_tenant_group_perm_effect CHECK (((effect)::text = ANY ((ARRAY['ALLOW'::character varying, 'DENY'::character varying])::text[])))
);


--
-- Name: tenant_group_tenant_group_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.tenant_group_tenant_group_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: tenant_group_tenant_group_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.tenant_group_tenant_group_id_seq OWNED BY public.tenant_group.tenant_group_id;


--
-- Name: tenant_module; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.tenant_module (
    tenant_id integer NOT NULL,
    module_key character varying(40) NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    note character varying(255),
    updated_by character varying(100),
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: tenant_provision_log; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.tenant_provision_log (
    log_id bigint NOT NULL,
    tenant_id bigint,
    script_id bigint,
    script_name character varying(150),
    status character varying(20),
    error_message text,
    duration_ms bigint,
    executed_by character varying(100),
    executed_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP
);


--
-- Name: tenant_provision_log_log_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.tenant_provision_log_log_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: tenant_provision_log_log_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.tenant_provision_log_log_id_seq OWNED BY public.tenant_provision_log.log_id;


--
-- Name: tenant_provision_script; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.tenant_provision_script (
    script_id bigint NOT NULL,
    script_name character varying(150) NOT NULL,
    script_order integer DEFAULT 100 NOT NULL,
    description text,
    script_sql text NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    continue_on_error boolean DEFAULT false NOT NULL,
    created_by character varying(100),
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp without time zone
);


--
-- Name: tenant_provision_script_script_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.tenant_provision_script_script_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: tenant_provision_script_script_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.tenant_provision_script_script_id_seq OWNED BY public.tenant_provision_script.script_id;


--
-- Name: tenant_setting; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.tenant_setting (
    setting_id integer NOT NULL,
    tenant_id integer,
    setting_key character varying(100) NOT NULL,
    setting_value text,
    setting_type character varying(20) DEFAULT 'STRING'::character varying,
    CONSTRAINT setting_type_check CHECK (((setting_type)::text = ANY ((ARRAY['STRING'::character varying, 'BOOLEAN'::character varying, 'NUMBER'::character varying, 'JSON'::character varying, 'ENCRYPTED'::character varying])::text[])))
);


--
-- Name: TABLE tenant_setting; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.tenant_setting IS 'Per-tenant key-value configuration. Sensitive values (e.g. S3 secret key) are stored with setting_type=ENCRYPTED using AES-256-GCM encryption. Encryption key is configured via app.encryption.key in application.properties.';


--
-- Name: tenant_setting_setting_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.tenant_setting_setting_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: tenant_setting_setting_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.tenant_setting_setting_id_seq OWNED BY public.tenant_setting.setting_id;


--
-- Name: tenant_tenant_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.tenant_tenant_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: tenant_tenant_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.tenant_tenant_id_seq OWNED BY public.tenant.tenant_id;


--
-- Name: terminal_channel_map; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.terminal_channel_map (
    id bigint NOT NULL,
    tenant_id integer,
    country_code character varying(2) NOT NULL,
    raw_type character varying(60) NOT NULL,
    channel character varying(20) NOT NULL,
    note character varying(120)
);


--
-- Name: terminal_channel_map_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.terminal_channel_map_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: terminal_channel_map_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.terminal_channel_map_id_seq OWNED BY public.terminal_channel_map.id;


--
-- Name: ticket_size_bucket; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ticket_size_bucket (
    id bigint NOT NULL,
    tenant_id integer,
    country_code character varying(2) NOT NULL,
    seq integer NOT NULL,
    min_amount numeric(19,4),
    max_amount numeric(19,4),
    label character varying(30) NOT NULL,
    note character varying(120)
);


--
-- Name: ticket_size_bucket_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.ticket_size_bucket_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ticket_size_bucket_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.ticket_size_bucket_id_seq OWNED BY public.ticket_size_bucket.id;


--
-- Name: user_region_access; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.user_region_access (
    access_id integer NOT NULL,
    user_id bigint,
    region_id integer
);


--
-- Name: user_region_access_access_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.user_region_access_access_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: user_region_access_access_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.user_region_access_access_id_seq OWNED BY public.user_region_access.access_id;


--
-- Name: user_role; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.user_role (
    map_id integer NOT NULL,
    user_id bigint,
    role_id integer
);


--
-- Name: user_role_map_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.user_role_map_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: user_role_map_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.user_role_map_id_seq OWNED BY public.user_role.map_id;


--
-- Name: user_tenant_access; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.user_tenant_access (
    access_id integer NOT NULL,
    user_id bigint,
    tenant_id integer,
    group_id bigint,
    role_in_tenant character varying(50),
    is_default_tenant boolean DEFAULT false
);


--
-- Name: user_tenant_access_access_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.user_tenant_access_access_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: user_tenant_access_access_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.user_tenant_access_access_id_seq OWNED BY public.user_tenant_access.access_id;


--
-- Name: users; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.users (
    user_id bigint NOT NULL,
    username character varying(50) NOT NULL,
    password_hash character varying(255) NOT NULL,
    email character varying(100),
    role character varying(50),
    is_active boolean DEFAULT true,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    last_login timestamp without time zone,
    must_change_password boolean DEFAULT false,
    password_changed_at timestamp without time zone,
    failed_login_attempts integer DEFAULT 0,
    locked_until timestamp without time zone,
    last_failed_login timestamp without time zone,
    sso_provider character varying(20),
    sso_id character varying(255),
    approval_status character varying(20) DEFAULT 'APPROVED'::character varying,
    display_name character varying(150),
    account_expires_at timestamp without time zone
);


--
-- Name: users_user_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.users_user_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: users_user_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.users_user_id_seq OWNED BY public.users.user_id;


--
-- Name: v_user_permission; Type: VIEW; Schema: public; Owner: -
--

CREATE VIEW public.v_user_permission AS
 SELECT uta.user_id,
    uta.tenant_id,
    uta.group_id,
    p.perm_code,
    p.menu_key,
    p.action,
    m.menu_id,
    m.module_key
   FROM ((((public.user_tenant_access uta
     JOIN public.tenant_group_perm tgp ON (((tgp.tenant_id = uta.tenant_id) AND (tgp.group_id = uta.group_id) AND ((tgp.effect)::text = 'ALLOW'::text))))
     JOIN public.sys_permission p ON (((p.perm_code)::text = (tgp.perm_code)::text)))
     JOIN public.sys_menu m ON ((((m.menu_key)::text = (p.menu_key)::text) AND m.is_active)))
     JOIN public.tenant_module tm ON (((tm.tenant_id = uta.tenant_id) AND ((tm.module_key)::text = (m.module_key)::text) AND tm.enabled)))
  WHERE (NOT (EXISTS ( SELECT 1
           FROM public.tenant_group_perm d
          WHERE ((d.tenant_id = uta.tenant_id) AND (d.group_id = uta.group_id) AND ((d.perm_code)::text = (p.perm_code)::text) AND ((d.effect)::text = 'DENY'::text)))));


--
-- Name: webhook_delivery; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.webhook_delivery (
    delivery_id bigint NOT NULL,
    tenant_id bigint NOT NULL,
    endpoint_id bigint NOT NULL,
    event_type character varying(80) NOT NULL,
    event_id character varying(64) NOT NULL,
    payload text NOT NULL,
    status character varying(16) DEFAULT 'PENDING'::character varying NOT NULL,
    attempts integer DEFAULT 0 NOT NULL,
    next_attempt_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    response_status integer,
    response_body character varying(500),
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    delivered_at timestamp without time zone
);


--
-- Name: webhook_delivery_delivery_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.webhook_delivery_delivery_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: webhook_delivery_delivery_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.webhook_delivery_delivery_id_seq OWNED BY public.webhook_delivery.delivery_id;


--
-- Name: webhook_endpoint; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.webhook_endpoint (
    endpoint_id bigint NOT NULL,
    tenant_id bigint NOT NULL,
    name character varying(120) NOT NULL,
    url character varying(1000) NOT NULL,
    secret character varying(500) NOT NULL,
    events text DEFAULT '[]'::text NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    created_by character varying(120),
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp without time zone,
    last_success_at timestamp without time zone,
    last_failure_at timestamp without time zone,
    consecutive_failures integer DEFAULT 0 NOT NULL
);


--
-- Name: webhook_endpoint_endpoint_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.webhook_endpoint_endpoint_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: webhook_endpoint_endpoint_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.webhook_endpoint_endpoint_id_seq OWNED BY public.webhook_endpoint.endpoint_id;


--
-- Name: fact_transaction_default; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public.fact_transaction ATTACH PARTITION public.fact_transaction_default DEFAULT;


--
-- Name: sum_daily_bank_default; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_bank ATTACH PARTITION public.sum_daily_bank_default DEFAULT;


--
-- Name: sum_daily_bank_y2024; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_bank ATTACH PARTITION public.sum_daily_bank_y2024 FOR VALUES FROM ('2024-01-01') TO ('2025-01-01');


--
-- Name: sum_daily_bank_y2025; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_bank ATTACH PARTITION public.sum_daily_bank_y2025 FOR VALUES FROM ('2025-01-01') TO ('2026-01-01');


--
-- Name: sum_daily_bank_y2026; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_bank ATTACH PARTITION public.sum_daily_bank_y2026 FOR VALUES FROM ('2026-01-01') TO ('2027-01-01');


--
-- Name: sum_daily_bank_y2027; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_bank ATTACH PARTITION public.sum_daily_bank_y2027 FOR VALUES FROM ('2027-01-01') TO ('2028-01-01');


--
-- Name: sum_daily_channel_default; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_channel ATTACH PARTITION public.sum_daily_channel_default DEFAULT;


--
-- Name: sum_daily_channel_y2024; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_channel ATTACH PARTITION public.sum_daily_channel_y2024 FOR VALUES FROM ('2024-01-01') TO ('2025-01-01');


--
-- Name: sum_daily_channel_y2025; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_channel ATTACH PARTITION public.sum_daily_channel_y2025 FOR VALUES FROM ('2025-01-01') TO ('2026-01-01');


--
-- Name: sum_daily_channel_y2026; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_channel ATTACH PARTITION public.sum_daily_channel_y2026 FOR VALUES FROM ('2026-01-01') TO ('2027-01-01');


--
-- Name: sum_daily_channel_y2027; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_channel ATTACH PARTITION public.sum_daily_channel_y2027 FOR VALUES FROM ('2027-01-01') TO ('2028-01-01');


--
-- Name: sum_daily_explorer_default; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_explorer ATTACH PARTITION public.sum_daily_explorer_default DEFAULT;


--
-- Name: sum_daily_explorer_y2024; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_explorer ATTACH PARTITION public.sum_daily_explorer_y2024 FOR VALUES FROM ('2024-01-01') TO ('2025-01-01');


--
-- Name: sum_daily_explorer_y2025; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_explorer ATTACH PARTITION public.sum_daily_explorer_y2025 FOR VALUES FROM ('2025-01-01') TO ('2026-01-01');


--
-- Name: sum_daily_explorer_y2026; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_explorer ATTACH PARTITION public.sum_daily_explorer_y2026 FOR VALUES FROM ('2026-01-01') TO ('2027-01-01');


--
-- Name: sum_daily_explorer_y2027; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_explorer ATTACH PARTITION public.sum_daily_explorer_y2027 FOR VALUES FROM ('2027-01-01') TO ('2028-01-01');


--
-- Name: sum_daily_finance_default; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_finance ATTACH PARTITION public.sum_daily_finance_default DEFAULT;


--
-- Name: sum_daily_finance_y2024; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_finance ATTACH PARTITION public.sum_daily_finance_y2024 FOR VALUES FROM ('2024-01-01') TO ('2025-01-01');


--
-- Name: sum_daily_finance_y2025; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_finance ATTACH PARTITION public.sum_daily_finance_y2025 FOR VALUES FROM ('2025-01-01') TO ('2026-01-01');


--
-- Name: sum_daily_finance_y2026; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_finance ATTACH PARTITION public.sum_daily_finance_y2026 FOR VALUES FROM ('2026-01-01') TO ('2027-01-01');


--
-- Name: sum_daily_finance_y2027; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_finance ATTACH PARTITION public.sum_daily_finance_y2027 FOR VALUES FROM ('2027-01-01') TO ('2028-01-01');


--
-- Name: sum_daily_full_default; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_full ATTACH PARTITION public.sum_daily_full_default DEFAULT;


--
-- Name: sum_daily_full_y2024; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_full ATTACH PARTITION public.sum_daily_full_y2024 FOR VALUES FROM ('2024-01-01') TO ('2025-01-01');


--
-- Name: sum_daily_full_y2025; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_full ATTACH PARTITION public.sum_daily_full_y2025 FOR VALUES FROM ('2025-01-01') TO ('2026-01-01');


--
-- Name: sum_daily_full_y2026; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_full ATTACH PARTITION public.sum_daily_full_y2026 FOR VALUES FROM ('2026-01-01') TO ('2027-01-01');


--
-- Name: sum_daily_full_y2027; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_full ATTACH PARTITION public.sum_daily_full_y2027 FOR VALUES FROM ('2027-01-01') TO ('2028-01-01');


--
-- Name: sum_daily_insight_default; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_insight ATTACH PARTITION public.sum_daily_insight_default DEFAULT;


--
-- Name: sum_daily_insight_y2024; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_insight ATTACH PARTITION public.sum_daily_insight_y2024 FOR VALUES FROM ('2024-01-01') TO ('2025-01-01');


--
-- Name: sum_daily_insight_y2025; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_insight ATTACH PARTITION public.sum_daily_insight_y2025 FOR VALUES FROM ('2025-01-01') TO ('2026-01-01');


--
-- Name: sum_daily_insight_y2026; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_insight ATTACH PARTITION public.sum_daily_insight_y2026 FOR VALUES FROM ('2026-01-01') TO ('2027-01-01');


--
-- Name: sum_daily_insight_y2027; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_insight ATTACH PARTITION public.sum_daily_insight_y2027 FOR VALUES FROM ('2027-01-01') TO ('2028-01-01');


--
-- Name: sum_daily_local_debit_bin_default; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_local_debit_bin ATTACH PARTITION public.sum_daily_local_debit_bin_default DEFAULT;


--
-- Name: sum_daily_local_debit_bin_y2024; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_local_debit_bin ATTACH PARTITION public.sum_daily_local_debit_bin_y2024 FOR VALUES FROM ('2024-01-01') TO ('2025-01-01');


--
-- Name: sum_daily_local_debit_bin_y2025; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_local_debit_bin ATTACH PARTITION public.sum_daily_local_debit_bin_y2025 FOR VALUES FROM ('2025-01-01') TO ('2026-01-01');


--
-- Name: sum_daily_local_debit_bin_y2026; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_local_debit_bin ATTACH PARTITION public.sum_daily_local_debit_bin_y2026 FOR VALUES FROM ('2026-01-01') TO ('2027-01-01');


--
-- Name: sum_daily_local_debit_bin_y2027; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_local_debit_bin ATTACH PARTITION public.sum_daily_local_debit_bin_y2027 FOR VALUES FROM ('2027-01-01') TO ('2028-01-01');


--
-- Name: sum_daily_merch_attr_default; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_merchant_attribute ATTACH PARTITION public.sum_daily_merch_attr_default DEFAULT;


--
-- Name: sum_daily_merch_attr_y2024; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_merchant_attribute ATTACH PARTITION public.sum_daily_merch_attr_y2024 FOR VALUES FROM ('2024-01-01') TO ('2025-01-01');


--
-- Name: sum_daily_merch_attr_y2025; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_merchant_attribute ATTACH PARTITION public.sum_daily_merch_attr_y2025 FOR VALUES FROM ('2025-01-01') TO ('2026-01-01');


--
-- Name: sum_daily_merch_attr_y2026; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_merchant_attribute ATTACH PARTITION public.sum_daily_merch_attr_y2026 FOR VALUES FROM ('2026-01-01') TO ('2027-01-01');


--
-- Name: sum_daily_merchant_default; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_merchant ATTACH PARTITION public.sum_daily_merchant_default DEFAULT;


--
-- Name: sum_daily_merchant_destination_default; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_merchant_destination ATTACH PARTITION public.sum_daily_merchant_destination_default DEFAULT;


--
-- Name: sum_daily_merchant_destination_y2024; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_merchant_destination ATTACH PARTITION public.sum_daily_merchant_destination_y2024 FOR VALUES FROM ('2024-01-01') TO ('2025-01-01');


--
-- Name: sum_daily_merchant_destination_y2025; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_merchant_destination ATTACH PARTITION public.sum_daily_merchant_destination_y2025 FOR VALUES FROM ('2025-01-01') TO ('2026-01-01');


--
-- Name: sum_daily_merchant_destination_y2026; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_merchant_destination ATTACH PARTITION public.sum_daily_merchant_destination_y2026 FOR VALUES FROM ('2026-01-01') TO ('2027-01-01');


--
-- Name: sum_daily_merchant_destination_y2027; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_merchant_destination ATTACH PARTITION public.sum_daily_merchant_destination_y2027 FOR VALUES FROM ('2027-01-01') TO ('2028-01-01');


--
-- Name: sum_daily_merchant_y2024; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_merchant ATTACH PARTITION public.sum_daily_merchant_y2024 FOR VALUES FROM ('2024-01-01') TO ('2025-01-01');


--
-- Name: sum_daily_merchant_y2025; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_merchant ATTACH PARTITION public.sum_daily_merchant_y2025 FOR VALUES FROM ('2025-01-01') TO ('2026-01-01');


--
-- Name: sum_daily_merchant_y2026; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_merchant ATTACH PARTITION public.sum_daily_merchant_y2026 FOR VALUES FROM ('2026-01-01') TO ('2027-01-01');


--
-- Name: sum_daily_scheme_default; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_scheme ATTACH PARTITION public.sum_daily_scheme_default DEFAULT;


--
-- Name: sum_daily_scheme_y2024; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_scheme ATTACH PARTITION public.sum_daily_scheme_y2024 FOR VALUES FROM ('2024-01-01') TO ('2025-01-01');


--
-- Name: sum_daily_scheme_y2025; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_scheme ATTACH PARTITION public.sum_daily_scheme_y2025 FOR VALUES FROM ('2025-01-01') TO ('2026-01-01');


--
-- Name: sum_daily_scheme_y2026; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_scheme ATTACH PARTITION public.sum_daily_scheme_y2026 FOR VALUES FROM ('2026-01-01') TO ('2027-01-01');


--
-- Name: sum_daily_scheme_y2027; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_scheme ATTACH PARTITION public.sum_daily_scheme_y2027 FOR VALUES FROM ('2027-01-01') TO ('2028-01-01');


--
-- Name: sum_daily_terminal_default; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_terminal ATTACH PARTITION public.sum_daily_terminal_default DEFAULT;


--
-- Name: sum_daily_terminal_y2024; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_terminal ATTACH PARTITION public.sum_daily_terminal_y2024 FOR VALUES FROM ('2024-01-01') TO ('2025-01-01');


--
-- Name: sum_daily_terminal_y2025; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_terminal ATTACH PARTITION public.sum_daily_terminal_y2025 FOR VALUES FROM ('2025-01-01') TO ('2026-01-01');


--
-- Name: sum_daily_terminal_y2026; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_terminal ATTACH PARTITION public.sum_daily_terminal_y2026 FOR VALUES FROM ('2026-01-01') TO ('2027-01-01');


--
-- Name: sum_daily_terminal_y2027; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_terminal ATTACH PARTITION public.sum_daily_terminal_y2027 FOR VALUES FROM ('2027-01-01') TO ('2028-01-01');


--
-- Name: access_request request_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.access_request ALTER COLUMN request_id SET DEFAULT nextval('public.access_request_request_id_seq'::regclass);


--
-- Name: ai_chat_history chat_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ai_chat_history ALTER COLUMN chat_id SET DEFAULT nextval('public.ai_chat_history_chat_id_seq'::regclass);


--
-- Name: alert_history alert_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.alert_history ALTER COLUMN alert_id SET DEFAULT nextval('public.alert_history_alert_id_seq'::regclass);


--
-- Name: alert_rule rule_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.alert_rule ALTER COLUMN rule_id SET DEFAULT nextval('public.alert_rule_rule_id_seq'::regclass);


--
-- Name: api_key key_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.api_key ALTER COLUMN key_id SET DEFAULT nextval('public.api_key_key_id_seq'::regclass);


--
-- Name: api_request_log log_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.api_request_log ALTER COLUMN log_id SET DEFAULT nextval('public.api_request_log_log_id_seq'::regclass);


--
-- Name: audit_log log_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.audit_log ALTER COLUMN log_id SET DEFAULT nextval('public.audit_log_log_id_seq'::regclass);


--
-- Name: bank_budget_target budget_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.bank_budget_target ALTER COLUMN budget_id SET DEFAULT nextval('public.bank_budget_target_budget_id_seq'::regclass);


--
-- Name: batch_run_log run_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.batch_run_log ALTER COLUMN run_id SET DEFAULT nextval('public.batch_run_log_run_id_seq'::regclass);


--
-- Name: dashboard_config config_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dashboard_config ALTER COLUMN config_id SET DEFAULT nextval('public.dashboard_config_config_id_seq'::regclass);


--
-- Name: data_source_config id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.data_source_config ALTER COLUMN id SET DEFAULT nextval('public.data_source_config_id_seq'::regclass);


--
-- Name: db_maintenance_run id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.db_maintenance_run ALTER COLUMN id SET DEFAULT nextval('public.db_maintenance_run_id_seq'::regclass);


--
-- Name: destination_token_map id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.destination_token_map ALTER COLUMN id SET DEFAULT nextval('public.destination_token_map_id_seq'::regclass);


--
-- Name: digest_dispatch id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.digest_dispatch ALTER COLUMN id SET DEFAULT nextval('public.digest_dispatch_id_seq'::regclass);


--
-- Name: dim_aggregator aggregator_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dim_aggregator ALTER COLUMN aggregator_id SET DEFAULT nextval('public.dim_aggregator_aggregator_id_seq'::regclass);


--
-- Name: dim_bank_account account_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dim_bank_account ALTER COLUMN account_id SET DEFAULT nextval('public.dim_bank_account_account_id_seq'::regclass);


--
-- Name: dim_merchant merchant_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dim_merchant ALTER COLUMN merchant_id SET DEFAULT nextval('public.dim_merchant_merchant_id_seq'::regclass);


--
-- Name: dim_store store_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dim_store ALTER COLUMN store_id SET DEFAULT nextval('public.dim_store_store_id_seq'::regclass);


--
-- Name: dim_terminal terminal_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dim_terminal ALTER COLUMN terminal_id SET DEFAULT nextval('public.dim_terminal_terminal_id_seq'::regclass);


--
-- Name: ecom_flat_fee id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ecom_flat_fee ALTER COLUMN id SET DEFAULT nextval('public.ecom_flat_fee_id_seq'::regclass);


--
-- Name: email_campaign id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.email_campaign ALTER COLUMN id SET DEFAULT nextval('public.email_campaign_id_seq'::regclass);


--
-- Name: email_campaign_log id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.email_campaign_log ALTER COLUMN id SET DEFAULT nextval('public.email_campaign_log_id_seq'::regclass);


--
-- Name: email_queue id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.email_queue ALTER COLUMN id SET DEFAULT nextval('public.email_queue_id_seq'::regclass);


--
-- Name: email_smtp_config id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.email_smtp_config ALTER COLUMN id SET DEFAULT nextval('public.email_smtp_config_id_seq'::regclass);


--
-- Name: email_template_config id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.email_template_config ALTER COLUMN id SET DEFAULT nextval('public.email_template_config_id_seq'::regclass);


--
-- Name: event_outbox id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.event_outbox ALTER COLUMN id SET DEFAULT nextval('public.event_outbox_id_seq'::regclass);


--
-- Name: explorer_alert id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.explorer_alert ALTER COLUMN id SET DEFAULT nextval('public.explorer_alert_id_seq'::regclass);


--
-- Name: explorer_master_item id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.explorer_master_item ALTER COLUMN id SET DEFAULT nextval('public.explorer_master_item_id_seq'::regclass);


--
-- Name: fact_dcc_revenue dcc_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.fact_dcc_revenue ALTER COLUMN dcc_id SET DEFAULT nextval('public.fact_dcc_revenue_dcc_id_seq'::regclass);


--
-- Name: fact_rental rental_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.fact_rental ALTER COLUMN rental_id SET DEFAULT nextval('public.fact_rental_rental_id_seq'::regclass);


--
-- Name: fact_transaction transaction_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.fact_transaction ALTER COLUMN transaction_id SET DEFAULT nextval('public.fact_transaction_transaction_id_seq'::regclass);


--
-- Name: feed_amount_contract id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.feed_amount_contract ALTER COLUMN id SET DEFAULT nextval('public.feed_amount_contract_id_seq'::regclass);


--
-- Name: fx_rate_daily fx_rate_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.fx_rate_daily ALTER COLUMN fx_rate_id SET DEFAULT nextval('public.fx_rate_daily_fx_rate_id_seq'::regclass);


--
-- Name: ingest_run id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ingest_run ALTER COLUMN id SET DEFAULT nextval('public.ingest_run_id_seq'::regclass);


--
-- Name: ingest_run_stage id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ingest_run_stage ALTER COLUMN id SET DEFAULT nextval('public.ingest_run_stage_id_seq'::regclass);


--
-- Name: integration_connection id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.integration_connection ALTER COLUMN id SET DEFAULT nextval('public.integration_connection_id_seq'::regclass);


--
-- Name: integration_report id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.integration_report ALTER COLUMN id SET DEFAULT nextval('public.integration_report_id_seq'::regclass);


--
-- Name: integration_run_log id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.integration_run_log ALTER COLUMN id SET DEFAULT nextval('public.integration_run_log_id_seq'::regclass);


--
-- Name: integration_schedule id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.integration_schedule ALTER COLUMN id SET DEFAULT nextval('public.integration_schedule_id_seq'::regclass);


--
-- Name: interchange_file id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.interchange_file ALTER COLUMN id SET DEFAULT nextval('public.interchange_file_id_seq'::regclass);


--
-- Name: interchange_normalization_detail detail_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.interchange_normalization_detail ALTER COLUMN detail_id SET DEFAULT nextval('public.interchange_normalization_detail_detail_id_seq'::regclass);


--
-- Name: interchange_normalization_run run_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.interchange_normalization_run ALTER COLUMN run_id SET DEFAULT nextval('public.interchange_normalization_run_run_id_seq'::regclass);


--
-- Name: interchange_rate_local id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.interchange_rate_local ALTER COLUMN id SET DEFAULT nextval('public.interchange_rate_local_id_seq'::regclass);


--
-- Name: interchange_transaction id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.interchange_transaction ALTER COLUMN id SET DEFAULT nextval('public.interchange_transaction_id_seq'::regclass);


--
-- Name: interchange_violation id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.interchange_violation ALTER COLUMN id SET DEFAULT nextval('public.interchange_violation_id_seq'::regclass);


--
-- Name: kpi_snapshot_daily snapshot_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.kpi_snapshot_daily ALTER COLUMN snapshot_id SET DEFAULT nextval('public.kpi_snapshot_daily_snapshot_id_seq'::regclass);


--
-- Name: kpi_snapshot_monthly snapshot_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.kpi_snapshot_monthly ALTER COLUMN snapshot_id SET DEFAULT nextval('public.kpi_snapshot_monthly_snapshot_id_seq'::regclass);


--
-- Name: login_mfa_token mfa_token_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.login_mfa_token ALTER COLUMN mfa_token_id SET DEFAULT nextval('public.login_mfa_token_mfa_token_id_seq'::regclass);


--
-- Name: mcc_sector_map id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mcc_sector_map ALTER COLUMN id SET DEFAULT nextval('public.mcc_sector_map_id_seq'::regclass);


--
-- Name: merchant_activity activity_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.merchant_activity ALTER COLUMN activity_id SET DEFAULT nextval('public.merchant_activity_activity_id_seq'::regclass);


--
-- Name: merchant_activity_summary summary_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.merchant_activity_summary ALTER COLUMN summary_id SET DEFAULT nextval('public.merchant_activity_summary_summary_id_seq'::regclass);


--
-- Name: merchant_churn_score churn_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.merchant_churn_score ALTER COLUMN churn_id SET DEFAULT nextval('public.merchant_churn_score_churn_id_seq'::regclass);


--
-- Name: merchant_contact contact_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.merchant_contact ALTER COLUMN contact_id SET DEFAULT nextval('public.merchant_contact_contact_id_seq'::regclass);


--
-- Name: merchant_contract contract_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.merchant_contract ALTER COLUMN contract_id SET DEFAULT nextval('public.merchant_contract_contract_id_seq'::regclass);


--
-- Name: merchant_daily_metrics id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.merchant_daily_metrics ALTER COLUMN id SET DEFAULT nextval('public.merchant_daily_metrics_id_seq'::regclass);


--
-- Name: merchant_document document_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.merchant_document ALTER COLUMN document_id SET DEFAULT nextval('public.merchant_document_document_id_seq'::regclass);


--
-- Name: merchant_note note_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.merchant_note ALTER COLUMN note_id SET DEFAULT nextval('public.merchant_note_note_id_seq'::regclass);


--
-- Name: merchant_opportunity_score score_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.merchant_opportunity_score ALTER COLUMN score_id SET DEFAULT nextval('public.merchant_opportunity_score_score_id_seq'::regclass);


--
-- Name: merchant_risk_profile profile_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.merchant_risk_profile ALTER COLUMN profile_id SET DEFAULT nextval('public.merchant_risk_profile_profile_id_seq'::regclass);


--
-- Name: merchant_sales_assignment_history history_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.merchant_sales_assignment_history ALTER COLUMN history_id SET DEFAULT nextval('public.merchant_sales_assignment_history_history_id_seq'::regclass);


--
-- Name: merchant_segment segment_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.merchant_segment ALTER COLUMN segment_id SET DEFAULT nextval('public.merchant_segment_segment_id_seq'::regclass);


--
-- Name: merchant_settlement_config config_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.merchant_settlement_config ALTER COLUMN config_id SET DEFAULT nextval('public.merchant_settlement_config_config_id_seq'::regclass);


--
-- Name: mpe_file id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mpe_file ALTER COLUMN id SET DEFAULT nextval('public.mpe_file_id_seq'::regclass);


--
-- Name: mpe_record id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mpe_record ALTER COLUMN id SET DEFAULT nextval('public.mpe_record_id_seq'::regclass);


--
-- Name: mpe_table_directory id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mpe_table_directory ALTER COLUMN id SET DEFAULT nextval('public.mpe_table_directory_id_seq'::regclass);


--
-- Name: password_history history_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.password_history ALTER COLUMN history_id SET DEFAULT nextval('public.password_history_history_id_seq'::regclass);


--
-- Name: password_reset_token token_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.password_reset_token ALTER COLUMN token_id SET DEFAULT nextval('public.password_reset_token_token_id_seq'::regclass);


--
-- Name: pdf_batch_log id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.pdf_batch_log ALTER COLUMN id SET DEFAULT nextval('public.pdf_batch_log_id_seq'::regclass);


--
-- Name: profitability_adjustment_rate id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.profitability_adjustment_rate ALTER COLUMN id SET DEFAULT nextval('public.profitability_adjustment_rate_id_seq'::regclass);


--
-- Name: ref_bin_range id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ref_bin_range ALTER COLUMN id SET DEFAULT nextval('public.ref_bin_range_id_seq'::regclass);


--
-- Name: ref_ecom_fx_rate id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ref_ecom_fx_rate ALTER COLUMN id SET DEFAULT nextval('public.ref_ecom_fx_rate_id_seq'::regclass);


--
-- Name: refresh_token id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.refresh_token ALTER COLUMN id SET DEFAULT nextval('public.refresh_token_id_seq'::regclass);


--
-- Name: region region_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.region ALTER COLUMN region_id SET DEFAULT nextval('public.region_region_id_seq'::regclass);


--
-- Name: report_query_config id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.report_query_config ALTER COLUMN id SET DEFAULT nextval('public.report_query_config_id_seq'::regclass);


--
-- Name: report_run_log id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.report_run_log ALTER COLUMN id SET DEFAULT nextval('public.report_run_log_id_seq'::regclass);


--
-- Name: report_schedule id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.report_schedule ALTER COLUMN id SET DEFAULT nextval('public.report_schedule_id_seq'::regclass);


--
-- Name: report_template id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.report_template ALTER COLUMN id SET DEFAULT nextval('public.report_template_id_seq'::regclass);


--
-- Name: revenue_leakage_flags flag_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.revenue_leakage_flags ALTER COLUMN flag_id SET DEFAULT nextval('public.revenue_leakage_flags_flag_id_seq'::regclass);


--
-- Name: role role_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.role ALTER COLUMN role_id SET DEFAULT nextval('public.role_role_id_seq'::regclass);


--
-- Name: sales_agent_profile id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sales_agent_profile ALTER COLUMN id SET DEFAULT nextval('public.sales_agent_profile_id_seq'::regclass);


--
-- Name: sales_agent_target target_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sales_agent_target ALTER COLUMN target_id SET DEFAULT nextval('public.sales_agent_target_target_id_seq'::regclass);


--
-- Name: sales_country_lead id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sales_country_lead ALTER COLUMN id SET DEFAULT nextval('public.sales_country_lead_id_seq'::regclass);


--
-- Name: sales_team_mapping id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sales_team_mapping ALTER COLUMN id SET DEFAULT nextval('public.sales_team_mapping_id_seq'::regclass);


--
-- Name: sales_user_assignment id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sales_user_assignment ALTER COLUMN id SET DEFAULT nextval('public.sales_user_assignment_id_seq'::regclass);


--
-- Name: saved_filter id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.saved_filter ALTER COLUMN id SET DEFAULT nextval('public.saved_filter_id_seq'::regclass);


--
-- Name: schema_migration_registry registry_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.schema_migration_registry ALTER COLUMN registry_id SET DEFAULT nextval('public.schema_migration_registry_registry_id_seq'::regclass);


--
-- Name: scheme_fee_rate id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.scheme_fee_rate ALTER COLUMN id SET DEFAULT nextval('public.scheme_fee_rate_id_seq'::regclass);


--
-- Name: stg_dcc_revenue_raw raw_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stg_dcc_revenue_raw ALTER COLUMN raw_id SET DEFAULT nextval('public.stg_dcc_revenue_raw_raw_id_seq'::regclass);


--
-- Name: stg_fx_rate_raw stg_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stg_fx_rate_raw ALTER COLUMN stg_id SET DEFAULT nextval('public.stg_fx_rate_raw_stg_id_seq'::regclass);


--
-- Name: stg_merchant_master_raw raw_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stg_merchant_master_raw ALTER COLUMN raw_id SET DEFAULT nextval('public.stg_merchant_master_raw_raw_id_seq'::regclass);


--
-- Name: stg_rental_raw raw_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stg_rental_raw ALTER COLUMN raw_id SET DEFAULT nextval('public.stg_rental_raw_raw_id_seq'::regclass);


--
-- Name: stg_trnx_raw raw_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stg_trnx_raw ALTER COLUMN raw_id SET DEFAULT nextval('public.stg_trnx_raw_raw_id_seq'::regclass);


--
-- Name: stg_visa_clearing_raw id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stg_visa_clearing_raw ALTER COLUMN id SET DEFAULT nextval('public.stg_visa_clearing_raw_id_seq'::regclass);


--
-- Name: sum_daily_bank summary_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_bank ALTER COLUMN summary_id SET DEFAULT nextval('public.sum_daily_bank_summary_id_seq'::regclass);


--
-- Name: sum_daily_channel summary_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_channel ALTER COLUMN summary_id SET DEFAULT nextval('public.sum_daily_channel_summary_id_seq'::regclass);


--
-- Name: sum_daily_explorer summary_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_explorer ALTER COLUMN summary_id SET DEFAULT nextval('public.sum_daily_explorer_summary_id_seq'::regclass);


--
-- Name: sum_daily_finance summary_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_finance ALTER COLUMN summary_id SET DEFAULT nextval('public.sum_daily_finance_summary_id_seq'::regclass);


--
-- Name: sum_daily_full summary_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_full ALTER COLUMN summary_id SET DEFAULT nextval('public.sum_daily_full_summary_id_seq'::regclass);


--
-- Name: sum_daily_insight summary_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_insight ALTER COLUMN summary_id SET DEFAULT nextval('public.sum_daily_insight_summary_id_seq'::regclass);


--
-- Name: sum_daily_local_debit_bin summary_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_local_debit_bin ALTER COLUMN summary_id SET DEFAULT nextval('public.sum_daily_local_debit_bin_summary_id_seq'::regclass);


--
-- Name: sum_daily_mcc summary_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_mcc ALTER COLUMN summary_id SET DEFAULT nextval('public.sum_daily_mcc_summary_id_seq'::regclass);


--
-- Name: sum_daily_merchant summary_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_merchant ALTER COLUMN summary_id SET DEFAULT nextval('public.sum_daily_merchant_summary_id_seq'::regclass);


--
-- Name: sum_daily_merchant_attribute id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_merchant_attribute ALTER COLUMN id SET DEFAULT nextval('public.sum_daily_merchant_attribute_id_seq'::regclass);


--
-- Name: sum_daily_merchant_destination summary_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_merchant_destination ALTER COLUMN summary_id SET DEFAULT nextval('public.sum_daily_merchant_destination_summary_id_seq'::regclass);


--
-- Name: sum_daily_scheme summary_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_scheme ALTER COLUMN summary_id SET DEFAULT nextval('public.sum_daily_scheme_summary_id_seq'::regclass);


--
-- Name: sum_daily_terminal summary_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_terminal ALTER COLUMN summary_id SET DEFAULT nextval('public.sum_daily_terminal_summary_id_seq'::regclass);


--
-- Name: sum_monthly_bank summary_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_monthly_bank ALTER COLUMN summary_id SET DEFAULT nextval('public.sum_monthly_bank_summary_id_seq'::regclass);


--
-- Name: sum_monthly_card id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_monthly_card ALTER COLUMN id SET DEFAULT nextval('public.sum_monthly_card_id_seq'::regclass);


--
-- Name: sum_monthly_insight summary_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_monthly_insight ALTER COLUMN summary_id SET DEFAULT nextval('public.sum_monthly_insight_summary_id_seq'::regclass);


--
-- Name: sum_monthly_merchant_metrics metric_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_monthly_merchant_metrics ALTER COLUMN metric_id SET DEFAULT nextval('public.sum_monthly_merchant_metrics_metric_id_seq'::regclass);


--
-- Name: sys_menu menu_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sys_menu ALTER COLUMN menu_id SET DEFAULT nextval('public.sys_menu_menu_id_seq'::regclass);


--
-- Name: sys_user_group group_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sys_user_group ALTER COLUMN group_id SET DEFAULT nextval('public.sys_user_group_group_id_seq'::regclass);


--
-- Name: tenant tenant_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tenant ALTER COLUMN tenant_id SET DEFAULT nextval('public.tenant_tenant_id_seq'::regclass);


--
-- Name: tenant_group tenant_group_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tenant_group ALTER COLUMN tenant_group_id SET DEFAULT nextval('public.tenant_group_tenant_group_id_seq'::regclass);


--
-- Name: tenant_provision_log log_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tenant_provision_log ALTER COLUMN log_id SET DEFAULT nextval('public.tenant_provision_log_log_id_seq'::regclass);


--
-- Name: tenant_provision_script script_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tenant_provision_script ALTER COLUMN script_id SET DEFAULT nextval('public.tenant_provision_script_script_id_seq'::regclass);


--
-- Name: tenant_setting setting_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tenant_setting ALTER COLUMN setting_id SET DEFAULT nextval('public.tenant_setting_setting_id_seq'::regclass);


--
-- Name: terminal_channel_map id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.terminal_channel_map ALTER COLUMN id SET DEFAULT nextval('public.terminal_channel_map_id_seq'::regclass);


--
-- Name: ticket_size_bucket id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ticket_size_bucket ALTER COLUMN id SET DEFAULT nextval('public.ticket_size_bucket_id_seq'::regclass);


--
-- Name: user_region_access access_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_region_access ALTER COLUMN access_id SET DEFAULT nextval('public.user_region_access_access_id_seq'::regclass);


--
-- Name: user_role map_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_role ALTER COLUMN map_id SET DEFAULT nextval('public.user_role_map_id_seq'::regclass);


--
-- Name: user_tenant_access access_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_tenant_access ALTER COLUMN access_id SET DEFAULT nextval('public.user_tenant_access_access_id_seq'::regclass);


--
-- Name: users user_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.users ALTER COLUMN user_id SET DEFAULT nextval('public.users_user_id_seq'::regclass);


--
-- Name: webhook_delivery delivery_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.webhook_delivery ALTER COLUMN delivery_id SET DEFAULT nextval('public.webhook_delivery_delivery_id_seq'::regclass);


--
-- Name: webhook_endpoint endpoint_id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.webhook_endpoint ALTER COLUMN endpoint_id SET DEFAULT nextval('public.webhook_endpoint_endpoint_id_seq'::regclass);


--
-- Name: access_request access_request_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.access_request
    ADD CONSTRAINT access_request_pkey PRIMARY KEY (request_id);


--
-- Name: ai_chat_history ai_chat_history_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ai_chat_history
    ADD CONSTRAINT ai_chat_history_pkey PRIMARY KEY (chat_id);


--
-- Name: alert_history alert_history_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.alert_history
    ADD CONSTRAINT alert_history_pkey PRIMARY KEY (alert_id);


--
-- Name: alert_rule alert_rule_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.alert_rule
    ADD CONSTRAINT alert_rule_pkey PRIMARY KEY (rule_id);


--
-- Name: api_idempotency api_idempotency_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.api_idempotency
    ADD CONSTRAINT api_idempotency_pkey PRIMARY KEY (tenant_id, idem_key);


--
-- Name: api_key api_key_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.api_key
    ADD CONSTRAINT api_key_pkey PRIMARY KEY (key_id);


--
-- Name: api_request_log api_request_log_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.api_request_log
    ADD CONSTRAINT api_request_log_pkey PRIMARY KEY (log_id);


--
-- Name: audit_log audit_log_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.audit_log
    ADD CONSTRAINT audit_log_pkey PRIMARY KEY (log_id);


--
-- Name: bank_budget_target bank_budget_target_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.bank_budget_target
    ADD CONSTRAINT bank_budget_target_pkey PRIMARY KEY (budget_id);


--
-- Name: batch_job_execution_context batch_job_execution_context_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.batch_job_execution_context
    ADD CONSTRAINT batch_job_execution_context_pkey PRIMARY KEY (job_execution_id);


--
-- Name: batch_job_execution batch_job_execution_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.batch_job_execution
    ADD CONSTRAINT batch_job_execution_pkey PRIMARY KEY (job_execution_id);


--
-- Name: batch_job_instance batch_job_instance_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.batch_job_instance
    ADD CONSTRAINT batch_job_instance_pkey PRIMARY KEY (job_instance_id);


--
-- Name: batch_run_log batch_run_log_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.batch_run_log
    ADD CONSTRAINT batch_run_log_pkey PRIMARY KEY (run_id);


--
-- Name: batch_step_execution_context batch_step_execution_context_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.batch_step_execution_context
    ADD CONSTRAINT batch_step_execution_context_pkey PRIMARY KEY (step_execution_id);


--
-- Name: batch_step_execution batch_step_execution_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.batch_step_execution
    ADD CONSTRAINT batch_step_execution_pkey PRIMARY KEY (step_execution_id);


--
-- Name: dashboard_config dashboard_config_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dashboard_config
    ADD CONSTRAINT dashboard_config_pkey PRIMARY KEY (config_id);


--
-- Name: dashboard_config dashboard_config_tenant_id_kpi_key_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dashboard_config
    ADD CONSTRAINT dashboard_config_tenant_id_kpi_key_key UNIQUE (tenant_id, kpi_key);


--
-- Name: data_source_config data_source_config_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.data_source_config
    ADD CONSTRAINT data_source_config_pkey PRIMARY KEY (id);


--
-- Name: db_maintenance_config db_maintenance_config_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.db_maintenance_config
    ADD CONSTRAINT db_maintenance_config_pkey PRIMARY KEY (id);


--
-- Name: db_maintenance_run db_maintenance_run_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.db_maintenance_run
    ADD CONSTRAINT db_maintenance_run_pkey PRIMARY KEY (id);


--
-- Name: destination_token_map destination_token_map_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.destination_token_map
    ADD CONSTRAINT destination_token_map_pkey PRIMARY KEY (id);


--
-- Name: digest_config digest_config_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.digest_config
    ADD CONSTRAINT digest_config_pkey PRIMARY KEY (tenant_id);


--
-- Name: digest_dispatch digest_dispatch_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.digest_dispatch
    ADD CONSTRAINT digest_dispatch_pkey PRIMARY KEY (id);


--
-- Name: digest_dispatch digest_dispatch_tenant_id_business_date_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.digest_dispatch
    ADD CONSTRAINT digest_dispatch_tenant_id_business_date_key UNIQUE (tenant_id, business_date);


--
-- Name: dim_aggregator dim_aggregator_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dim_aggregator
    ADD CONSTRAINT dim_aggregator_pkey PRIMARY KEY (aggregator_id);


--
-- Name: dim_bank_account dim_bank_account_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dim_bank_account
    ADD CONSTRAINT dim_bank_account_pkey PRIMARY KEY (account_id);


--
-- Name: dim_merchant dim_merchant_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dim_merchant
    ADD CONSTRAINT dim_merchant_pkey PRIMARY KEY (merchant_id);


--
-- Name: dim_merchant dim_merchant_tenant_id_internal_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dim_merchant
    ADD CONSTRAINT dim_merchant_tenant_id_internal_id_key UNIQUE (tenant_id, internal_id);


--
-- Name: dim_store dim_store_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dim_store
    ADD CONSTRAINT dim_store_pkey PRIMARY KEY (store_id);


--
-- Name: dim_store dim_store_tenant_id_internal_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dim_store
    ADD CONSTRAINT dim_store_tenant_id_internal_id_key UNIQUE (tenant_id, internal_id);


--
-- Name: dim_terminal dim_terminal_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dim_terminal
    ADD CONSTRAINT dim_terminal_pkey PRIMARY KEY (terminal_id);


--
-- Name: dim_terminal dim_terminal_tenant_id_internal_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dim_terminal
    ADD CONSTRAINT dim_terminal_tenant_id_internal_id_key UNIQUE (tenant_id, internal_id);


--
-- Name: ecom_flat_fee ecom_flat_fee_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ecom_flat_fee
    ADD CONSTRAINT ecom_flat_fee_pkey PRIMARY KEY (id);


--
-- Name: email_campaign_log email_campaign_log_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.email_campaign_log
    ADD CONSTRAINT email_campaign_log_pkey PRIMARY KEY (id);


--
-- Name: email_campaign email_campaign_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.email_campaign
    ADD CONSTRAINT email_campaign_pkey PRIMARY KEY (id);


--
-- Name: email_queue email_queue_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.email_queue
    ADD CONSTRAINT email_queue_pkey PRIMARY KEY (id);


--
-- Name: email_smtp_config email_smtp_config_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.email_smtp_config
    ADD CONSTRAINT email_smtp_config_pkey PRIMARY KEY (id);


--
-- Name: email_template_config email_template_config_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.email_template_config
    ADD CONSTRAINT email_template_config_pkey PRIMARY KEY (id);


--
-- Name: event_outbox event_outbox_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.event_outbox
    ADD CONSTRAINT event_outbox_pkey PRIMARY KEY (id);


--
-- Name: explorer_alert explorer_alert_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.explorer_alert
    ADD CONSTRAINT explorer_alert_pkey PRIMARY KEY (id);


--
-- Name: explorer_master_item explorer_master_item_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.explorer_master_item
    ADD CONSTRAINT explorer_master_item_pkey PRIMARY KEY (id);


--
-- Name: fact_dcc_revenue fact_dcc_revenue_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.fact_dcc_revenue
    ADD CONSTRAINT fact_dcc_revenue_pkey PRIMARY KEY (dcc_id);


--
-- Name: fact_rental fact_rental_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.fact_rental
    ADD CONSTRAINT fact_rental_pkey PRIMARY KEY (rental_id);


--
-- Name: fact_transaction fact_transaction_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.fact_transaction
    ADD CONSTRAINT fact_transaction_pkey PRIMARY KEY (transaction_id, payment_date);


--
-- Name: fact_transaction_default fact_transaction_default_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.fact_transaction_default
    ADD CONSTRAINT fact_transaction_default_pkey PRIMARY KEY (transaction_id, payment_date);


--
-- Name: feed_amount_contract feed_amount_contract_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.feed_amount_contract
    ADD CONSTRAINT feed_amount_contract_pkey PRIMARY KEY (id);


--
-- Name: fx_rate_daily fx_rate_daily_currency_code_rate_date_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.fx_rate_daily
    ADD CONSTRAINT fx_rate_daily_currency_code_rate_date_key UNIQUE (currency_code, rate_date);


--
-- Name: fx_rate_daily fx_rate_daily_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.fx_rate_daily
    ADD CONSTRAINT fx_rate_daily_pkey PRIMARY KEY (fx_rate_id);


--
-- Name: ingest_day_coverage ingest_day_coverage_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ingest_day_coverage
    ADD CONSTRAINT ingest_day_coverage_pkey PRIMARY KEY (tenant_id, txn_date);


--
-- Name: ingest_expectation ingest_expectation_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ingest_expectation
    ADD CONSTRAINT ingest_expectation_pkey PRIMARY KEY (tenant_id);


--
-- Name: ingest_run ingest_run_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ingest_run
    ADD CONSTRAINT ingest_run_pkey PRIMARY KEY (id);


--
-- Name: ingest_run_stage ingest_run_stage_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ingest_run_stage
    ADD CONSTRAINT ingest_run_stage_pkey PRIMARY KEY (id);


--
-- Name: integration_connection integration_connection_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.integration_connection
    ADD CONSTRAINT integration_connection_pkey PRIMARY KEY (id);


--
-- Name: integration_report integration_report_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.integration_report
    ADD CONSTRAINT integration_report_pkey PRIMARY KEY (id);


--
-- Name: integration_run_log integration_run_log_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.integration_run_log
    ADD CONSTRAINT integration_run_log_pkey PRIMARY KEY (id);


--
-- Name: integration_schedule integration_schedule_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.integration_schedule
    ADD CONSTRAINT integration_schedule_pkey PRIMARY KEY (id);


--
-- Name: interchange_file interchange_file_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.interchange_file
    ADD CONSTRAINT interchange_file_pkey PRIMARY KEY (id);


--
-- Name: interchange_normalization_detail interchange_normalization_detail_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.interchange_normalization_detail
    ADD CONSTRAINT interchange_normalization_detail_pkey PRIMARY KEY (detail_id);


--
-- Name: interchange_normalization_run interchange_normalization_run_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.interchange_normalization_run
    ADD CONSTRAINT interchange_normalization_run_pkey PRIMARY KEY (run_id);


--
-- Name: interchange_rate_local interchange_rate_local_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.interchange_rate_local
    ADD CONSTRAINT interchange_rate_local_pkey PRIMARY KEY (id);


--
-- Name: interchange_transaction interchange_transaction_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.interchange_transaction
    ADD CONSTRAINT interchange_transaction_pkey PRIMARY KEY (id);


--
-- Name: interchange_violation interchange_violation_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.interchange_violation
    ADD CONSTRAINT interchange_violation_pkey PRIMARY KEY (id);


--
-- Name: batch_job_instance job_inst_un; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.batch_job_instance
    ADD CONSTRAINT job_inst_un UNIQUE (job_name, job_key);


--
-- Name: kpi_snapshot_daily kpi_snapshot_daily_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.kpi_snapshot_daily
    ADD CONSTRAINT kpi_snapshot_daily_pkey PRIMARY KEY (snapshot_id);


--
-- Name: kpi_snapshot_daily kpi_snapshot_daily_tenant_id_snapshot_date_metric_key_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.kpi_snapshot_daily
    ADD CONSTRAINT kpi_snapshot_daily_tenant_id_snapshot_date_metric_key_key UNIQUE (tenant_id, snapshot_date, metric_key);


--
-- Name: kpi_snapshot_monthly kpi_snapshot_monthly_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.kpi_snapshot_monthly
    ADD CONSTRAINT kpi_snapshot_monthly_pkey PRIMARY KEY (snapshot_id);


--
-- Name: kpi_snapshot_monthly kpi_snapshot_monthly_tenant_id_month_key_metric_key_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.kpi_snapshot_monthly
    ADD CONSTRAINT kpi_snapshot_monthly_tenant_id_month_key_metric_key_key UNIQUE (tenant_id, month_key, metric_key);


--
-- Name: login_mfa_token login_mfa_token_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.login_mfa_token
    ADD CONSTRAINT login_mfa_token_pkey PRIMARY KEY (mfa_token_id);


--
-- Name: login_mfa_token login_mfa_token_ticket_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.login_mfa_token
    ADD CONSTRAINT login_mfa_token_ticket_key UNIQUE (ticket);


--
-- Name: mcc_sector_map mcc_sector_map_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mcc_sector_map
    ADD CONSTRAINT mcc_sector_map_pkey PRIMARY KEY (id);


--
-- Name: merchant_activity merchant_activity_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.merchant_activity
    ADD CONSTRAINT merchant_activity_pkey PRIMARY KEY (activity_id);


--
-- Name: merchant_activity_summary merchant_activity_summary_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.merchant_activity_summary
    ADD CONSTRAINT merchant_activity_summary_pkey PRIMARY KEY (summary_id);


--
-- Name: merchant_activity_summary merchant_activity_summary_tenant_id_merchant_id_calc_date_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.merchant_activity_summary
    ADD CONSTRAINT merchant_activity_summary_tenant_id_merchant_id_calc_date_key UNIQUE (tenant_id, merchant_id, calc_date);


--
-- Name: merchant_activity merchant_activity_tenant_id_merchant_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.merchant_activity
    ADD CONSTRAINT merchant_activity_tenant_id_merchant_id_key UNIQUE (tenant_id, merchant_id);


--
-- Name: merchant_churn_score merchant_churn_score_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.merchant_churn_score
    ADD CONSTRAINT merchant_churn_score_pkey PRIMARY KEY (churn_id);


--
-- Name: merchant_contact merchant_contact_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.merchant_contact
    ADD CONSTRAINT merchant_contact_pkey PRIMARY KEY (contact_id);


--
-- Name: merchant_contract merchant_contract_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.merchant_contract
    ADD CONSTRAINT merchant_contract_pkey PRIMARY KEY (contract_id);


--
-- Name: merchant_daily_metrics merchant_daily_metrics_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.merchant_daily_metrics
    ADD CONSTRAINT merchant_daily_metrics_pkey PRIMARY KEY (id);


--
-- Name: merchant_document merchant_document_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.merchant_document
    ADD CONSTRAINT merchant_document_pkey PRIMARY KEY (document_id);


--
-- Name: merchant_note merchant_note_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.merchant_note
    ADD CONSTRAINT merchant_note_pkey PRIMARY KEY (note_id);


--
-- Name: merchant_opportunity_score merchant_opportunity_score_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.merchant_opportunity_score
    ADD CONSTRAINT merchant_opportunity_score_pkey PRIMARY KEY (score_id);


--
-- Name: merchant_opportunity_score merchant_opportunity_score_tenant_id_merchant_id_calc_date_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.merchant_opportunity_score
    ADD CONSTRAINT merchant_opportunity_score_tenant_id_merchant_id_calc_date_key UNIQUE (tenant_id, merchant_id, calc_date);


--
-- Name: merchant_risk_profile merchant_risk_profile_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.merchant_risk_profile
    ADD CONSTRAINT merchant_risk_profile_pkey PRIMARY KEY (profile_id);


--
-- Name: merchant_sales_assignment_history merchant_sales_assignment_history_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.merchant_sales_assignment_history
    ADD CONSTRAINT merchant_sales_assignment_history_pkey PRIMARY KEY (history_id);


--
-- Name: merchant_segment merchant_segment_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.merchant_segment
    ADD CONSTRAINT merchant_segment_pkey PRIMARY KEY (segment_id);


--
-- Name: merchant_settlement_config merchant_settlement_config_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.merchant_settlement_config
    ADD CONSTRAINT merchant_settlement_config_pkey PRIMARY KEY (config_id);


--
-- Name: mpe_file mpe_file_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mpe_file
    ADD CONSTRAINT mpe_file_pkey PRIMARY KEY (id);


--
-- Name: mpe_file mpe_file_sha256_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mpe_file
    ADD CONSTRAINT mpe_file_sha256_key UNIQUE (sha256);


--
-- Name: mpe_record mpe_record_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mpe_record
    ADD CONSTRAINT mpe_record_pkey PRIMARY KEY (id);


--
-- Name: mpe_table_directory mpe_table_directory_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mpe_table_directory
    ADD CONSTRAINT mpe_table_directory_pkey PRIMARY KEY (id);


--
-- Name: password_history password_history_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.password_history
    ADD CONSTRAINT password_history_pkey PRIMARY KEY (history_id);


--
-- Name: password_reset_token password_reset_token_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.password_reset_token
    ADD CONSTRAINT password_reset_token_pkey PRIMARY KEY (token_id);


--
-- Name: password_reset_token password_reset_token_token_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.password_reset_token
    ADD CONSTRAINT password_reset_token_token_key UNIQUE (token);


--
-- Name: pdf_batch_log pdf_batch_log_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.pdf_batch_log
    ADD CONSTRAINT pdf_batch_log_pkey PRIMARY KEY (id);


--
-- Name: pdf_job_status pdf_job_status_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.pdf_job_status
    ADD CONSTRAINT pdf_job_status_pkey PRIMARY KEY (job_id);


--
-- Name: profitability_adjustment_rate profitability_adjustment_rate_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.profitability_adjustment_rate
    ADD CONSTRAINT profitability_adjustment_rate_pkey PRIMARY KEY (id);


--
-- Name: rbac_version rbac_version_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.rbac_version
    ADD CONSTRAINT rbac_version_pkey PRIMARY KEY (tenant_id);


--
-- Name: ref_bin ref_bin_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ref_bin
    ADD CONSTRAINT ref_bin_pkey PRIMARY KEY (bin);


--
-- Name: ref_bin_product_tier ref_bin_product_tier_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ref_bin_product_tier
    ADD CONSTRAINT ref_bin_product_tier_pkey PRIMARY KEY (product_code);


--
-- Name: ref_bin_range ref_bin_range_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ref_bin_range
    ADD CONSTRAINT ref_bin_range_pkey PRIMARY KEY (id);


--
-- Name: ref_card_scheme ref_card_scheme_code_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ref_card_scheme
    ADD CONSTRAINT ref_card_scheme_code_key UNIQUE (code);


--
-- Name: ref_card_scheme ref_card_scheme_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ref_card_scheme
    ADD CONSTRAINT ref_card_scheme_pkey PRIMARY KEY (id);


--
-- Name: ref_country ref_country_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ref_country
    ADD CONSTRAINT ref_country_pkey PRIMARY KEY (country_code);


--
-- Name: ref_ecom_fx_rate ref_ecom_fx_rate_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ref_ecom_fx_rate
    ADD CONSTRAINT ref_ecom_fx_rate_pkey PRIMARY KEY (id);


--
-- Name: ref_mcc_category ref_mcc_category_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ref_mcc_category
    ADD CONSTRAINT ref_mcc_category_pkey PRIMARY KEY (mcc);


--
-- Name: ref_tenant_bin_bank ref_tenant_bin_bank_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ref_tenant_bin_bank
    ADD CONSTRAINT ref_tenant_bin_bank_pkey PRIMARY KEY (tenant_id, bin);


--
-- Name: refresh_token refresh_token_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.refresh_token
    ADD CONSTRAINT refresh_token_pkey PRIMARY KEY (id);


--
-- Name: refresh_token refresh_token_token_hash_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.refresh_token
    ADD CONSTRAINT refresh_token_token_hash_key UNIQUE (token_hash);


--
-- Name: region region_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.region
    ADD CONSTRAINT region_pkey PRIMARY KEY (region_id);


--
-- Name: region region_region_name_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.region
    ADD CONSTRAINT region_region_name_key UNIQUE (region_name);


--
-- Name: report_cache_version report_cache_version_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.report_cache_version
    ADD CONSTRAINT report_cache_version_pkey PRIMARY KEY (tenant_id);


--
-- Name: report_query_config report_query_config_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.report_query_config
    ADD CONSTRAINT report_query_config_pkey PRIMARY KEY (id);


--
-- Name: report_run_log report_run_log_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.report_run_log
    ADD CONSTRAINT report_run_log_pkey PRIMARY KEY (id);


--
-- Name: report_schedule report_schedule_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.report_schedule
    ADD CONSTRAINT report_schedule_pkey PRIMARY KEY (id);


--
-- Name: report_template report_template_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.report_template
    ADD CONSTRAINT report_template_pkey PRIMARY KEY (id);


--
-- Name: revenue_leakage_flags revenue_leakage_flags_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.revenue_leakage_flags
    ADD CONSTRAINT revenue_leakage_flags_pkey PRIMARY KEY (flag_id);


--
-- Name: role role_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.role
    ADD CONSTRAINT role_pkey PRIMARY KEY (role_id);


--
-- Name: role role_role_name_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.role
    ADD CONSTRAINT role_role_name_key UNIQUE (role_name);


--
-- Name: sales_agent_profile sales_agent_profile_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sales_agent_profile
    ADD CONSTRAINT sales_agent_profile_pkey PRIMARY KEY (id);


--
-- Name: sales_agent_target sales_agent_target_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sales_agent_target
    ADD CONSTRAINT sales_agent_target_pkey PRIMARY KEY (target_id);


--
-- Name: sales_country_lead sales_country_lead_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sales_country_lead
    ADD CONSTRAINT sales_country_lead_pkey PRIMARY KEY (id);


--
-- Name: sales_team_mapping sales_team_mapping_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sales_team_mapping
    ADD CONSTRAINT sales_team_mapping_pkey PRIMARY KEY (id);


--
-- Name: sales_user_assignment sales_user_assignment_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sales_user_assignment
    ADD CONSTRAINT sales_user_assignment_pkey PRIMARY KEY (id);


--
-- Name: saved_filter saved_filter_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.saved_filter
    ADD CONSTRAINT saved_filter_pkey PRIMARY KEY (id);


--
-- Name: schema_migration_log schema_migration_log_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.schema_migration_log
    ADD CONSTRAINT schema_migration_log_pkey PRIMARY KEY (filename);


--
-- Name: schema_migration_registry schema_migration_registry_migration_name_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.schema_migration_registry
    ADD CONSTRAINT schema_migration_registry_migration_name_key UNIQUE (migration_name);


--
-- Name: schema_migration_registry schema_migration_registry_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.schema_migration_registry
    ADD CONSTRAINT schema_migration_registry_pkey PRIMARY KEY (registry_id);


--
-- Name: scheme_fee_rate scheme_fee_rate_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.scheme_fee_rate
    ADD CONSTRAINT scheme_fee_rate_pkey PRIMARY KEY (id);


--
-- Name: shedlock shedlock_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.shedlock
    ADD CONSTRAINT shedlock_pkey PRIMARY KEY (name);


--
-- Name: sso_state_token sso_state_token_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sso_state_token
    ADD CONSTRAINT sso_state_token_pkey PRIMARY KEY (state_token);


--
-- Name: stg_dcc_revenue_raw stg_dcc_revenue_raw_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stg_dcc_revenue_raw
    ADD CONSTRAINT stg_dcc_revenue_raw_pkey PRIMARY KEY (raw_id);


--
-- Name: stg_fx_rate_raw stg_fx_rate_raw_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stg_fx_rate_raw
    ADD CONSTRAINT stg_fx_rate_raw_pkey PRIMARY KEY (stg_id);


--
-- Name: stg_merchant_master_raw stg_merchant_master_raw_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stg_merchant_master_raw
    ADD CONSTRAINT stg_merchant_master_raw_pkey PRIMARY KEY (raw_id);


--
-- Name: stg_rental_raw stg_rental_raw_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stg_rental_raw
    ADD CONSTRAINT stg_rental_raw_pkey PRIMARY KEY (raw_id);


--
-- Name: stg_trnx_raw stg_trnx_raw_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stg_trnx_raw
    ADD CONSTRAINT stg_trnx_raw_pkey PRIMARY KEY (raw_id);


--
-- Name: stg_visa_clearing_raw stg_visa_clearing_raw_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stg_visa_clearing_raw
    ADD CONSTRAINT stg_visa_clearing_raw_pkey PRIMARY KEY (id);


--
-- Name: sum_daily_bank sum_daily_bank_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_bank
    ADD CONSTRAINT sum_daily_bank_pkey PRIMARY KEY (summary_id, business_date);


--
-- Name: sum_daily_bank_default sum_daily_bank_default_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_bank_default
    ADD CONSTRAINT sum_daily_bank_default_pkey PRIMARY KEY (summary_id, business_date);


--
-- Name: sum_daily_bank sum_daily_bank_tenant_id_business_date_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_bank
    ADD CONSTRAINT sum_daily_bank_tenant_id_business_date_key UNIQUE (tenant_id, business_date);


--
-- Name: sum_daily_bank_default sum_daily_bank_default_tenant_id_business_date_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_bank_default
    ADD CONSTRAINT sum_daily_bank_default_tenant_id_business_date_key UNIQUE (tenant_id, business_date);


--
-- Name: sum_daily_bank_y2024 sum_daily_bank_y2024_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_bank_y2024
    ADD CONSTRAINT sum_daily_bank_y2024_pkey PRIMARY KEY (summary_id, business_date);


--
-- Name: sum_daily_bank_y2024 sum_daily_bank_y2024_tenant_id_business_date_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_bank_y2024
    ADD CONSTRAINT sum_daily_bank_y2024_tenant_id_business_date_key UNIQUE (tenant_id, business_date);


--
-- Name: sum_daily_bank_y2025 sum_daily_bank_y2025_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_bank_y2025
    ADD CONSTRAINT sum_daily_bank_y2025_pkey PRIMARY KEY (summary_id, business_date);


--
-- Name: sum_daily_bank_y2025 sum_daily_bank_y2025_tenant_id_business_date_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_bank_y2025
    ADD CONSTRAINT sum_daily_bank_y2025_tenant_id_business_date_key UNIQUE (tenant_id, business_date);


--
-- Name: sum_daily_bank_y2026 sum_daily_bank_y2026_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_bank_y2026
    ADD CONSTRAINT sum_daily_bank_y2026_pkey PRIMARY KEY (summary_id, business_date);


--
-- Name: sum_daily_bank_y2026 sum_daily_bank_y2026_tenant_id_business_date_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_bank_y2026
    ADD CONSTRAINT sum_daily_bank_y2026_tenant_id_business_date_key UNIQUE (tenant_id, business_date);


--
-- Name: sum_daily_bank_y2027 sum_daily_bank_y2027_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_bank_y2027
    ADD CONSTRAINT sum_daily_bank_y2027_pkey PRIMARY KEY (summary_id, business_date);


--
-- Name: sum_daily_bank_y2027 sum_daily_bank_y2027_tenant_id_business_date_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_bank_y2027
    ADD CONSTRAINT sum_daily_bank_y2027_tenant_id_business_date_key UNIQUE (tenant_id, business_date);


--
-- Name: sum_daily_channel sum_daily_channel_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_channel
    ADD CONSTRAINT sum_daily_channel_pkey PRIMARY KEY (summary_id, business_date);


--
-- Name: sum_daily_channel_default sum_daily_channel_default_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_channel_default
    ADD CONSTRAINT sum_daily_channel_default_pkey PRIMARY KEY (summary_id, business_date);


--
-- Name: sum_daily_channel sum_daily_channel_tenant_id_business_date_channel_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_channel
    ADD CONSTRAINT sum_daily_channel_tenant_id_business_date_channel_key UNIQUE (tenant_id, business_date, channel);


--
-- Name: sum_daily_channel_default sum_daily_channel_default_tenant_id_business_date_channel_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_channel_default
    ADD CONSTRAINT sum_daily_channel_default_tenant_id_business_date_channel_key UNIQUE (tenant_id, business_date, channel);


--
-- Name: sum_daily_channel_y2024 sum_daily_channel_y2024_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_channel_y2024
    ADD CONSTRAINT sum_daily_channel_y2024_pkey PRIMARY KEY (summary_id, business_date);


--
-- Name: sum_daily_channel_y2024 sum_daily_channel_y2024_tenant_id_business_date_channel_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_channel_y2024
    ADD CONSTRAINT sum_daily_channel_y2024_tenant_id_business_date_channel_key UNIQUE (tenant_id, business_date, channel);


--
-- Name: sum_daily_channel_y2025 sum_daily_channel_y2025_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_channel_y2025
    ADD CONSTRAINT sum_daily_channel_y2025_pkey PRIMARY KEY (summary_id, business_date);


--
-- Name: sum_daily_channel_y2025 sum_daily_channel_y2025_tenant_id_business_date_channel_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_channel_y2025
    ADD CONSTRAINT sum_daily_channel_y2025_tenant_id_business_date_channel_key UNIQUE (tenant_id, business_date, channel);


--
-- Name: sum_daily_channel_y2026 sum_daily_channel_y2026_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_channel_y2026
    ADD CONSTRAINT sum_daily_channel_y2026_pkey PRIMARY KEY (summary_id, business_date);


--
-- Name: sum_daily_channel_y2026 sum_daily_channel_y2026_tenant_id_business_date_channel_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_channel_y2026
    ADD CONSTRAINT sum_daily_channel_y2026_tenant_id_business_date_channel_key UNIQUE (tenant_id, business_date, channel);


--
-- Name: sum_daily_channel_y2027 sum_daily_channel_y2027_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_channel_y2027
    ADD CONSTRAINT sum_daily_channel_y2027_pkey PRIMARY KEY (summary_id, business_date);


--
-- Name: sum_daily_channel_y2027 sum_daily_channel_y2027_tenant_id_business_date_channel_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_channel_y2027
    ADD CONSTRAINT sum_daily_channel_y2027_tenant_id_business_date_channel_key UNIQUE (tenant_id, business_date, channel);


--
-- Name: sum_daily_explorer sum_daily_explorer_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_explorer
    ADD CONSTRAINT sum_daily_explorer_pkey PRIMARY KEY (summary_id, business_date);


--
-- Name: sum_daily_explorer_default sum_daily_explorer_default_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_explorer_default
    ADD CONSTRAINT sum_daily_explorer_default_pkey PRIMARY KEY (summary_id, business_date);


--
-- Name: sum_daily_explorer sum_daily_explorer_tenant_id_business_date_merchant_id_stor_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_explorer
    ADD CONSTRAINT sum_daily_explorer_tenant_id_business_date_merchant_id_stor_key UNIQUE (tenant_id, business_date, merchant_id, store_id, terminal_id, transaction_type, card_scheme, card_type, destination, channel, txn_currency, is_opt_in);


--
-- Name: sum_daily_explorer_default sum_daily_explorer_default_tenant_id_business_date_merchant_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_explorer_default
    ADD CONSTRAINT sum_daily_explorer_default_tenant_id_business_date_merchant_key UNIQUE (tenant_id, business_date, merchant_id, store_id, terminal_id, transaction_type, card_scheme, card_type, destination, channel, txn_currency, is_opt_in);


--
-- Name: sum_daily_explorer_y2024 sum_daily_explorer_y2024_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_explorer_y2024
    ADD CONSTRAINT sum_daily_explorer_y2024_pkey PRIMARY KEY (summary_id, business_date);


--
-- Name: sum_daily_explorer_y2024 sum_daily_explorer_y2024_tenant_id_business_date_merchant_i_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_explorer_y2024
    ADD CONSTRAINT sum_daily_explorer_y2024_tenant_id_business_date_merchant_i_key UNIQUE (tenant_id, business_date, merchant_id, store_id, terminal_id, transaction_type, card_scheme, card_type, destination, channel, txn_currency, is_opt_in);


--
-- Name: sum_daily_explorer_y2025 sum_daily_explorer_y2025_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_explorer_y2025
    ADD CONSTRAINT sum_daily_explorer_y2025_pkey PRIMARY KEY (summary_id, business_date);


--
-- Name: sum_daily_explorer_y2025 sum_daily_explorer_y2025_tenant_id_business_date_merchant_i_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_explorer_y2025
    ADD CONSTRAINT sum_daily_explorer_y2025_tenant_id_business_date_merchant_i_key UNIQUE (tenant_id, business_date, merchant_id, store_id, terminal_id, transaction_type, card_scheme, card_type, destination, channel, txn_currency, is_opt_in);


--
-- Name: sum_daily_explorer_y2026 sum_daily_explorer_y2026_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_explorer_y2026
    ADD CONSTRAINT sum_daily_explorer_y2026_pkey PRIMARY KEY (summary_id, business_date);


--
-- Name: sum_daily_explorer_y2026 sum_daily_explorer_y2026_tenant_id_business_date_merchant_i_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_explorer_y2026
    ADD CONSTRAINT sum_daily_explorer_y2026_tenant_id_business_date_merchant_i_key UNIQUE (tenant_id, business_date, merchant_id, store_id, terminal_id, transaction_type, card_scheme, card_type, destination, channel, txn_currency, is_opt_in);


--
-- Name: sum_daily_explorer_y2027 sum_daily_explorer_y2027_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_explorer_y2027
    ADD CONSTRAINT sum_daily_explorer_y2027_pkey PRIMARY KEY (summary_id, business_date);


--
-- Name: sum_daily_explorer_y2027 sum_daily_explorer_y2027_tenant_id_business_date_merchant_i_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_explorer_y2027
    ADD CONSTRAINT sum_daily_explorer_y2027_tenant_id_business_date_merchant_i_key UNIQUE (tenant_id, business_date, merchant_id, store_id, terminal_id, transaction_type, card_scheme, card_type, destination, channel, txn_currency, is_opt_in);


--
-- Name: sum_daily_finance sum_daily_finance_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_finance
    ADD CONSTRAINT sum_daily_finance_pkey PRIMARY KEY (summary_id, business_date);


--
-- Name: sum_daily_finance_default sum_daily_finance_default_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_finance_default
    ADD CONSTRAINT sum_daily_finance_default_pkey PRIMARY KEY (summary_id, business_date);


--
-- Name: sum_daily_finance sum_daily_finance_tenant_id_business_date_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_finance
    ADD CONSTRAINT sum_daily_finance_tenant_id_business_date_key UNIQUE (tenant_id, business_date);


--
-- Name: sum_daily_finance_default sum_daily_finance_default_tenant_id_business_date_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_finance_default
    ADD CONSTRAINT sum_daily_finance_default_tenant_id_business_date_key UNIQUE (tenant_id, business_date);


--
-- Name: sum_daily_finance_rollup sum_daily_finance_rollup_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_finance_rollup
    ADD CONSTRAINT sum_daily_finance_rollup_pkey PRIMARY KEY (tenant_id, business_date);


--
-- Name: sum_daily_finance_y2024 sum_daily_finance_y2024_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_finance_y2024
    ADD CONSTRAINT sum_daily_finance_y2024_pkey PRIMARY KEY (summary_id, business_date);


--
-- Name: sum_daily_finance_y2024 sum_daily_finance_y2024_tenant_id_business_date_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_finance_y2024
    ADD CONSTRAINT sum_daily_finance_y2024_tenant_id_business_date_key UNIQUE (tenant_id, business_date);


--
-- Name: sum_daily_finance_y2025 sum_daily_finance_y2025_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_finance_y2025
    ADD CONSTRAINT sum_daily_finance_y2025_pkey PRIMARY KEY (summary_id, business_date);


--
-- Name: sum_daily_finance_y2025 sum_daily_finance_y2025_tenant_id_business_date_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_finance_y2025
    ADD CONSTRAINT sum_daily_finance_y2025_tenant_id_business_date_key UNIQUE (tenant_id, business_date);


--
-- Name: sum_daily_finance_y2026 sum_daily_finance_y2026_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_finance_y2026
    ADD CONSTRAINT sum_daily_finance_y2026_pkey PRIMARY KEY (summary_id, business_date);


--
-- Name: sum_daily_finance_y2026 sum_daily_finance_y2026_tenant_id_business_date_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_finance_y2026
    ADD CONSTRAINT sum_daily_finance_y2026_tenant_id_business_date_key UNIQUE (tenant_id, business_date);


--
-- Name: sum_daily_finance_y2027 sum_daily_finance_y2027_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_finance_y2027
    ADD CONSTRAINT sum_daily_finance_y2027_pkey PRIMARY KEY (summary_id, business_date);


--
-- Name: sum_daily_finance_y2027 sum_daily_finance_y2027_tenant_id_business_date_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_finance_y2027
    ADD CONSTRAINT sum_daily_finance_y2027_tenant_id_business_date_key UNIQUE (tenant_id, business_date);


--
-- Name: sum_daily_full sum_daily_full_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_full
    ADD CONSTRAINT sum_daily_full_pkey PRIMARY KEY (summary_id, business_date);


--
-- Name: sum_daily_full_default sum_daily_full_default_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_full_default
    ADD CONSTRAINT sum_daily_full_default_pkey PRIMARY KEY (summary_id, business_date);


--
-- Name: sum_daily_full sum_daily_full_tenant_id_business_date_merchant_id_store_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_full
    ADD CONSTRAINT sum_daily_full_tenant_id_business_date_merchant_id_store_id_key UNIQUE (tenant_id, business_date, merchant_id, store_id, mcc, channel, destination, card_scheme, card_type, is_opt_in);


--
-- Name: sum_daily_full_default sum_daily_full_default_tenant_id_business_date_merchant_id__key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_full_default
    ADD CONSTRAINT sum_daily_full_default_tenant_id_business_date_merchant_id__key UNIQUE (tenant_id, business_date, merchant_id, store_id, mcc, channel, destination, card_scheme, card_type, is_opt_in);


--
-- Name: sum_daily_full_y2024 sum_daily_full_y2024_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_full_y2024
    ADD CONSTRAINT sum_daily_full_y2024_pkey PRIMARY KEY (summary_id, business_date);


--
-- Name: sum_daily_full_y2024 sum_daily_full_y2024_tenant_id_business_date_merchant_id_st_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_full_y2024
    ADD CONSTRAINT sum_daily_full_y2024_tenant_id_business_date_merchant_id_st_key UNIQUE (tenant_id, business_date, merchant_id, store_id, mcc, channel, destination, card_scheme, card_type, is_opt_in);


--
-- Name: sum_daily_full_y2025 sum_daily_full_y2025_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_full_y2025
    ADD CONSTRAINT sum_daily_full_y2025_pkey PRIMARY KEY (summary_id, business_date);


--
-- Name: sum_daily_full_y2025 sum_daily_full_y2025_tenant_id_business_date_merchant_id_st_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_full_y2025
    ADD CONSTRAINT sum_daily_full_y2025_tenant_id_business_date_merchant_id_st_key UNIQUE (tenant_id, business_date, merchant_id, store_id, mcc, channel, destination, card_scheme, card_type, is_opt_in);


--
-- Name: sum_daily_full_y2026 sum_daily_full_y2026_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_full_y2026
    ADD CONSTRAINT sum_daily_full_y2026_pkey PRIMARY KEY (summary_id, business_date);


--
-- Name: sum_daily_full_y2026 sum_daily_full_y2026_tenant_id_business_date_merchant_id_st_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_full_y2026
    ADD CONSTRAINT sum_daily_full_y2026_tenant_id_business_date_merchant_id_st_key UNIQUE (tenant_id, business_date, merchant_id, store_id, mcc, channel, destination, card_scheme, card_type, is_opt_in);


--
-- Name: sum_daily_full_y2027 sum_daily_full_y2027_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_full_y2027
    ADD CONSTRAINT sum_daily_full_y2027_pkey PRIMARY KEY (summary_id, business_date);


--
-- Name: sum_daily_full_y2027 sum_daily_full_y2027_tenant_id_business_date_merchant_id_st_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_full_y2027
    ADD CONSTRAINT sum_daily_full_y2027_tenant_id_business_date_merchant_id_st_key UNIQUE (tenant_id, business_date, merchant_id, store_id, mcc, channel, destination, card_scheme, card_type, is_opt_in);


--
-- Name: sum_daily_insight sum_daily_insight_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_insight
    ADD CONSTRAINT sum_daily_insight_pkey PRIMARY KEY (summary_id, business_date);


--
-- Name: sum_daily_insight_default sum_daily_insight_default_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_insight_default
    ADD CONSTRAINT sum_daily_insight_default_pkey PRIMARY KEY (summary_id, business_date);


--
-- Name: sum_daily_insight sum_daily_insight_tenant_id_business_date_merchant_id_store_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_insight
    ADD CONSTRAINT sum_daily_insight_tenant_id_business_date_merchant_id_store_key UNIQUE (tenant_id, business_date, merchant_id, store_id, terminal_id, card_scheme, card_type, destination, channel, is_opt_in);


--
-- Name: sum_daily_insight_default sum_daily_insight_default_tenant_id_business_date_merchant__key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_insight_default
    ADD CONSTRAINT sum_daily_insight_default_tenant_id_business_date_merchant__key UNIQUE (tenant_id, business_date, merchant_id, store_id, terminal_id, card_scheme, card_type, destination, channel, is_opt_in);


--
-- Name: sum_daily_insight_y2024 sum_daily_insight_y2024_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_insight_y2024
    ADD CONSTRAINT sum_daily_insight_y2024_pkey PRIMARY KEY (summary_id, business_date);


--
-- Name: sum_daily_insight_y2024 sum_daily_insight_y2024_tenant_id_business_date_merchant_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_insight_y2024
    ADD CONSTRAINT sum_daily_insight_y2024_tenant_id_business_date_merchant_id_key UNIQUE (tenant_id, business_date, merchant_id, store_id, terminal_id, card_scheme, card_type, destination, channel, is_opt_in);


--
-- Name: sum_daily_insight_y2025 sum_daily_insight_y2025_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_insight_y2025
    ADD CONSTRAINT sum_daily_insight_y2025_pkey PRIMARY KEY (summary_id, business_date);


--
-- Name: sum_daily_insight_y2025 sum_daily_insight_y2025_tenant_id_business_date_merchant_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_insight_y2025
    ADD CONSTRAINT sum_daily_insight_y2025_tenant_id_business_date_merchant_id_key UNIQUE (tenant_id, business_date, merchant_id, store_id, terminal_id, card_scheme, card_type, destination, channel, is_opt_in);


--
-- Name: sum_daily_insight_y2026 sum_daily_insight_y2026_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_insight_y2026
    ADD CONSTRAINT sum_daily_insight_y2026_pkey PRIMARY KEY (summary_id, business_date);


--
-- Name: sum_daily_insight_y2026 sum_daily_insight_y2026_tenant_id_business_date_merchant_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_insight_y2026
    ADD CONSTRAINT sum_daily_insight_y2026_tenant_id_business_date_merchant_id_key UNIQUE (tenant_id, business_date, merchant_id, store_id, terminal_id, card_scheme, card_type, destination, channel, is_opt_in);


--
-- Name: sum_daily_insight_y2027 sum_daily_insight_y2027_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_insight_y2027
    ADD CONSTRAINT sum_daily_insight_y2027_pkey PRIMARY KEY (summary_id, business_date);


--
-- Name: sum_daily_insight_y2027 sum_daily_insight_y2027_tenant_id_business_date_merchant_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_insight_y2027
    ADD CONSTRAINT sum_daily_insight_y2027_tenant_id_business_date_merchant_id_key UNIQUE (tenant_id, business_date, merchant_id, store_id, terminal_id, card_scheme, card_type, destination, channel, is_opt_in);


--
-- Name: sum_daily_local_debit_bin sum_daily_local_debit_bin_tenant_id_business_date_merchant__key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_local_debit_bin
    ADD CONSTRAINT sum_daily_local_debit_bin_tenant_id_business_date_merchant__key UNIQUE (tenant_id, business_date, merchant_id, bin6);


--
-- Name: sum_daily_local_debit_bin_default sum_daily_local_debit_bin_def_tenant_id_business_date_merch_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_local_debit_bin_default
    ADD CONSTRAINT sum_daily_local_debit_bin_def_tenant_id_business_date_merch_key UNIQUE (tenant_id, business_date, merchant_id, bin6);


--
-- Name: sum_daily_local_debit_bin sum_daily_local_debit_bin_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_local_debit_bin
    ADD CONSTRAINT sum_daily_local_debit_bin_pkey PRIMARY KEY (summary_id, business_date);


--
-- Name: sum_daily_local_debit_bin_default sum_daily_local_debit_bin_default_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_local_debit_bin_default
    ADD CONSTRAINT sum_daily_local_debit_bin_default_pkey PRIMARY KEY (summary_id, business_date);


--
-- Name: sum_daily_local_debit_bin_y2024 sum_daily_local_debit_bin_y2024_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_local_debit_bin_y2024
    ADD CONSTRAINT sum_daily_local_debit_bin_y2024_pkey PRIMARY KEY (summary_id, business_date);


--
-- Name: sum_daily_local_debit_bin_y2025 sum_daily_local_debit_bin_y2025_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_local_debit_bin_y2025
    ADD CONSTRAINT sum_daily_local_debit_bin_y2025_pkey PRIMARY KEY (summary_id, business_date);


--
-- Name: sum_daily_local_debit_bin_y2026 sum_daily_local_debit_bin_y2026_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_local_debit_bin_y2026
    ADD CONSTRAINT sum_daily_local_debit_bin_y2026_pkey PRIMARY KEY (summary_id, business_date);


--
-- Name: sum_daily_local_debit_bin_y2027 sum_daily_local_debit_bin_y2027_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_local_debit_bin_y2027
    ADD CONSTRAINT sum_daily_local_debit_bin_y2027_pkey PRIMARY KEY (summary_id, business_date);


--
-- Name: sum_daily_local_debit_bin_y2025 sum_daily_local_debit_bin_y20_tenant_id_business_date_merc_key1; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_local_debit_bin_y2025
    ADD CONSTRAINT sum_daily_local_debit_bin_y20_tenant_id_business_date_merc_key1 UNIQUE (tenant_id, business_date, merchant_id, bin6);


--
-- Name: sum_daily_local_debit_bin_y2026 sum_daily_local_debit_bin_y20_tenant_id_business_date_merc_key2; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_local_debit_bin_y2026
    ADD CONSTRAINT sum_daily_local_debit_bin_y20_tenant_id_business_date_merc_key2 UNIQUE (tenant_id, business_date, merchant_id, bin6);


--
-- Name: sum_daily_local_debit_bin_y2027 sum_daily_local_debit_bin_y20_tenant_id_business_date_merc_key3; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_local_debit_bin_y2027
    ADD CONSTRAINT sum_daily_local_debit_bin_y20_tenant_id_business_date_merc_key3 UNIQUE (tenant_id, business_date, merchant_id, bin6);


--
-- Name: sum_daily_local_debit_bin_y2024 sum_daily_local_debit_bin_y20_tenant_id_business_date_merch_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_local_debit_bin_y2024
    ADD CONSTRAINT sum_daily_local_debit_bin_y20_tenant_id_business_date_merch_key UNIQUE (tenant_id, business_date, merchant_id, bin6);


--
-- Name: sum_daily_mcc sum_daily_mcc_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_mcc
    ADD CONSTRAINT sum_daily_mcc_pkey PRIMARY KEY (summary_id);


--
-- Name: sum_daily_mcc sum_daily_mcc_tenant_id_business_date_mcc_card_scheme_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_mcc
    ADD CONSTRAINT sum_daily_mcc_tenant_id_business_date_mcc_card_scheme_key UNIQUE (tenant_id, business_date, mcc, card_scheme);


--
-- Name: sum_daily_merchant_attribute sum_daily_merchant_attribute_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_merchant_attribute
    ADD CONSTRAINT sum_daily_merchant_attribute_pkey PRIMARY KEY (id, business_date);


--
-- Name: sum_daily_merch_attr_default sum_daily_merch_attr_default_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_merch_attr_default
    ADD CONSTRAINT sum_daily_merch_attr_default_pkey PRIMARY KEY (id, business_date);


--
-- Name: sum_daily_merchant_attribute sum_daily_merchant_attribute_tenant_id_merchant_id_business_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_merchant_attribute
    ADD CONSTRAINT sum_daily_merchant_attribute_tenant_id_merchant_id_business_key UNIQUE (tenant_id, merchant_id, business_date, attribute_type, attribute_value);


--
-- Name: sum_daily_merch_attr_default sum_daily_merch_attr_default_tenant_id_merchant_id_business_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_merch_attr_default
    ADD CONSTRAINT sum_daily_merch_attr_default_tenant_id_merchant_id_business_key UNIQUE (tenant_id, merchant_id, business_date, attribute_type, attribute_value);


--
-- Name: sum_daily_merch_attr_y2024 sum_daily_merch_attr_y2024_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_merch_attr_y2024
    ADD CONSTRAINT sum_daily_merch_attr_y2024_pkey PRIMARY KEY (id, business_date);


--
-- Name: sum_daily_merch_attr_y2024 sum_daily_merch_attr_y2024_tenant_id_merchant_id_business_d_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_merch_attr_y2024
    ADD CONSTRAINT sum_daily_merch_attr_y2024_tenant_id_merchant_id_business_d_key UNIQUE (tenant_id, merchant_id, business_date, attribute_type, attribute_value);


--
-- Name: sum_daily_merch_attr_y2025 sum_daily_merch_attr_y2025_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_merch_attr_y2025
    ADD CONSTRAINT sum_daily_merch_attr_y2025_pkey PRIMARY KEY (id, business_date);


--
-- Name: sum_daily_merch_attr_y2025 sum_daily_merch_attr_y2025_tenant_id_merchant_id_business_d_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_merch_attr_y2025
    ADD CONSTRAINT sum_daily_merch_attr_y2025_tenant_id_merchant_id_business_d_key UNIQUE (tenant_id, merchant_id, business_date, attribute_type, attribute_value);


--
-- Name: sum_daily_merch_attr_y2026 sum_daily_merch_attr_y2026_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_merch_attr_y2026
    ADD CONSTRAINT sum_daily_merch_attr_y2026_pkey PRIMARY KEY (id, business_date);


--
-- Name: sum_daily_merch_attr_y2026 sum_daily_merch_attr_y2026_tenant_id_merchant_id_business_d_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_merch_attr_y2026
    ADD CONSTRAINT sum_daily_merch_attr_y2026_tenant_id_merchant_id_business_d_key UNIQUE (tenant_id, merchant_id, business_date, attribute_type, attribute_value);


--
-- Name: sum_daily_merchant sum_daily_merchant_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_merchant
    ADD CONSTRAINT sum_daily_merchant_pkey PRIMARY KEY (summary_id, business_date);


--
-- Name: sum_daily_merchant_default sum_daily_merchant_default_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_merchant_default
    ADD CONSTRAINT sum_daily_merchant_default_pkey PRIMARY KEY (summary_id, business_date);


--
-- Name: sum_daily_merchant sum_daily_merchant_tenant_id_business_date_merchant_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_merchant
    ADD CONSTRAINT sum_daily_merchant_tenant_id_business_date_merchant_id_key UNIQUE (tenant_id, business_date, merchant_id);


--
-- Name: sum_daily_merchant_default sum_daily_merchant_default_tenant_id_business_date_merchant_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_merchant_default
    ADD CONSTRAINT sum_daily_merchant_default_tenant_id_business_date_merchant_key UNIQUE (tenant_id, business_date, merchant_id);


--
-- Name: sum_daily_merchant_destination sum_daily_merchant_destinatio_tenant_id_business_date_merch_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_merchant_destination
    ADD CONSTRAINT sum_daily_merchant_destinatio_tenant_id_business_date_merch_key UNIQUE (tenant_id, business_date, merchant_id, destination);


--
-- Name: sum_daily_merchant_destination_y2024 sum_daily_merchant_destinatio_tenant_id_business_date_merc_key1; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_merchant_destination_y2024
    ADD CONSTRAINT sum_daily_merchant_destinatio_tenant_id_business_date_merc_key1 UNIQUE (tenant_id, business_date, merchant_id, destination);


--
-- Name: sum_daily_merchant_destination_y2025 sum_daily_merchant_destinatio_tenant_id_business_date_merc_key2; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_merchant_destination_y2025
    ADD CONSTRAINT sum_daily_merchant_destinatio_tenant_id_business_date_merc_key2 UNIQUE (tenant_id, business_date, merchant_id, destination);


--
-- Name: sum_daily_merchant_destination_y2026 sum_daily_merchant_destinatio_tenant_id_business_date_merc_key3; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_merchant_destination_y2026
    ADD CONSTRAINT sum_daily_merchant_destinatio_tenant_id_business_date_merc_key3 UNIQUE (tenant_id, business_date, merchant_id, destination);


--
-- Name: sum_daily_merchant_destination_y2027 sum_daily_merchant_destinatio_tenant_id_business_date_merc_key4; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_merchant_destination_y2027
    ADD CONSTRAINT sum_daily_merchant_destinatio_tenant_id_business_date_merc_key4 UNIQUE (tenant_id, business_date, merchant_id, destination);


--
-- Name: sum_daily_merchant_destination_default sum_daily_merchant_destinatio_tenant_id_business_date_merc_key5; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_merchant_destination_default
    ADD CONSTRAINT sum_daily_merchant_destinatio_tenant_id_business_date_merc_key5 UNIQUE (tenant_id, business_date, merchant_id, destination);


--
-- Name: sum_daily_merchant_destination sum_daily_merchant_destination_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_merchant_destination
    ADD CONSTRAINT sum_daily_merchant_destination_pkey PRIMARY KEY (summary_id, business_date);


--
-- Name: sum_daily_merchant_destination_default sum_daily_merchant_destination_default_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_merchant_destination_default
    ADD CONSTRAINT sum_daily_merchant_destination_default_pkey PRIMARY KEY (summary_id, business_date);


--
-- Name: sum_daily_merchant_destination_y2024 sum_daily_merchant_destination_y2024_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_merchant_destination_y2024
    ADD CONSTRAINT sum_daily_merchant_destination_y2024_pkey PRIMARY KEY (summary_id, business_date);


--
-- Name: sum_daily_merchant_destination_y2025 sum_daily_merchant_destination_y2025_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_merchant_destination_y2025
    ADD CONSTRAINT sum_daily_merchant_destination_y2025_pkey PRIMARY KEY (summary_id, business_date);


--
-- Name: sum_daily_merchant_destination_y2026 sum_daily_merchant_destination_y2026_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_merchant_destination_y2026
    ADD CONSTRAINT sum_daily_merchant_destination_y2026_pkey PRIMARY KEY (summary_id, business_date);


--
-- Name: sum_daily_merchant_destination_y2027 sum_daily_merchant_destination_y2027_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_merchant_destination_y2027
    ADD CONSTRAINT sum_daily_merchant_destination_y2027_pkey PRIMARY KEY (summary_id, business_date);


--
-- Name: sum_daily_merchant_y2024 sum_daily_merchant_y2024_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_merchant_y2024
    ADD CONSTRAINT sum_daily_merchant_y2024_pkey PRIMARY KEY (summary_id, business_date);


--
-- Name: sum_daily_merchant_y2024 sum_daily_merchant_y2024_tenant_id_business_date_merchant_i_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_merchant_y2024
    ADD CONSTRAINT sum_daily_merchant_y2024_tenant_id_business_date_merchant_i_key UNIQUE (tenant_id, business_date, merchant_id);


--
-- Name: sum_daily_merchant_y2025 sum_daily_merchant_y2025_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_merchant_y2025
    ADD CONSTRAINT sum_daily_merchant_y2025_pkey PRIMARY KEY (summary_id, business_date);


--
-- Name: sum_daily_merchant_y2025 sum_daily_merchant_y2025_tenant_id_business_date_merchant_i_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_merchant_y2025
    ADD CONSTRAINT sum_daily_merchant_y2025_tenant_id_business_date_merchant_i_key UNIQUE (tenant_id, business_date, merchant_id);


--
-- Name: sum_daily_merchant_y2026 sum_daily_merchant_y2026_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_merchant_y2026
    ADD CONSTRAINT sum_daily_merchant_y2026_pkey PRIMARY KEY (summary_id, business_date);


--
-- Name: sum_daily_merchant_y2026 sum_daily_merchant_y2026_tenant_id_business_date_merchant_i_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_merchant_y2026
    ADD CONSTRAINT sum_daily_merchant_y2026_tenant_id_business_date_merchant_i_key UNIQUE (tenant_id, business_date, merchant_id);


--
-- Name: sum_daily_scheme sum_daily_scheme_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_scheme
    ADD CONSTRAINT sum_daily_scheme_pkey PRIMARY KEY (summary_id, business_date);


--
-- Name: sum_daily_scheme_default sum_daily_scheme_default_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_scheme_default
    ADD CONSTRAINT sum_daily_scheme_default_pkey PRIMARY KEY (summary_id, business_date);


--
-- Name: sum_daily_scheme sum_daily_scheme_tenant_id_business_date_card_scheme_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_scheme
    ADD CONSTRAINT sum_daily_scheme_tenant_id_business_date_card_scheme_key UNIQUE (tenant_id, business_date, card_scheme);


--
-- Name: sum_daily_scheme_default sum_daily_scheme_default_tenant_id_business_date_card_schem_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_scheme_default
    ADD CONSTRAINT sum_daily_scheme_default_tenant_id_business_date_card_schem_key UNIQUE (tenant_id, business_date, card_scheme);


--
-- Name: sum_daily_scheme_y2024 sum_daily_scheme_y2024_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_scheme_y2024
    ADD CONSTRAINT sum_daily_scheme_y2024_pkey PRIMARY KEY (summary_id, business_date);


--
-- Name: sum_daily_scheme_y2024 sum_daily_scheme_y2024_tenant_id_business_date_card_scheme_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_scheme_y2024
    ADD CONSTRAINT sum_daily_scheme_y2024_tenant_id_business_date_card_scheme_key UNIQUE (tenant_id, business_date, card_scheme);


--
-- Name: sum_daily_scheme_y2025 sum_daily_scheme_y2025_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_scheme_y2025
    ADD CONSTRAINT sum_daily_scheme_y2025_pkey PRIMARY KEY (summary_id, business_date);


--
-- Name: sum_daily_scheme_y2025 sum_daily_scheme_y2025_tenant_id_business_date_card_scheme_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_scheme_y2025
    ADD CONSTRAINT sum_daily_scheme_y2025_tenant_id_business_date_card_scheme_key UNIQUE (tenant_id, business_date, card_scheme);


--
-- Name: sum_daily_scheme_y2026 sum_daily_scheme_y2026_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_scheme_y2026
    ADD CONSTRAINT sum_daily_scheme_y2026_pkey PRIMARY KEY (summary_id, business_date);


--
-- Name: sum_daily_scheme_y2026 sum_daily_scheme_y2026_tenant_id_business_date_card_scheme_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_scheme_y2026
    ADD CONSTRAINT sum_daily_scheme_y2026_tenant_id_business_date_card_scheme_key UNIQUE (tenant_id, business_date, card_scheme);


--
-- Name: sum_daily_scheme_y2027 sum_daily_scheme_y2027_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_scheme_y2027
    ADD CONSTRAINT sum_daily_scheme_y2027_pkey PRIMARY KEY (summary_id, business_date);


--
-- Name: sum_daily_scheme_y2027 sum_daily_scheme_y2027_tenant_id_business_date_card_scheme_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_scheme_y2027
    ADD CONSTRAINT sum_daily_scheme_y2027_tenant_id_business_date_card_scheme_key UNIQUE (tenant_id, business_date, card_scheme);


--
-- Name: sum_daily_terminal sum_daily_terminal_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_terminal
    ADD CONSTRAINT sum_daily_terminal_pkey PRIMARY KEY (summary_id, business_date);


--
-- Name: sum_daily_terminal_default sum_daily_terminal_default_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_terminal_default
    ADD CONSTRAINT sum_daily_terminal_default_pkey PRIMARY KEY (summary_id, business_date);


--
-- Name: sum_daily_terminal_y2024 sum_daily_terminal_y2024_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_terminal_y2024
    ADD CONSTRAINT sum_daily_terminal_y2024_pkey PRIMARY KEY (summary_id, business_date);


--
-- Name: sum_daily_terminal_y2025 sum_daily_terminal_y2025_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_terminal_y2025
    ADD CONSTRAINT sum_daily_terminal_y2025_pkey PRIMARY KEY (summary_id, business_date);


--
-- Name: sum_daily_terminal_y2026 sum_daily_terminal_y2026_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_terminal_y2026
    ADD CONSTRAINT sum_daily_terminal_y2026_pkey PRIMARY KEY (summary_id, business_date);


--
-- Name: sum_daily_terminal_y2027 sum_daily_terminal_y2027_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_terminal_y2027
    ADD CONSTRAINT sum_daily_terminal_y2027_pkey PRIMARY KEY (summary_id, business_date);


--
-- Name: sum_monthly_bank sum_monthly_bank_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_monthly_bank
    ADD CONSTRAINT sum_monthly_bank_pkey PRIMARY KEY (summary_id);


--
-- Name: sum_monthly_bank sum_monthly_bank_tenant_id_month_key_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_monthly_bank
    ADD CONSTRAINT sum_monthly_bank_tenant_id_month_key_key UNIQUE (tenant_id, month_key);


--
-- Name: sum_monthly_card sum_monthly_card_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_monthly_card
    ADD CONSTRAINT sum_monthly_card_pkey PRIMARY KEY (id);


--
-- Name: sum_monthly_card sum_monthly_card_tenant_id_merchant_id_month_key_card_numbe_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_monthly_card
    ADD CONSTRAINT sum_monthly_card_tenant_id_merchant_id_month_key_card_numbe_key UNIQUE (tenant_id, merchant_id, month_key, card_number);


--
-- Name: sum_monthly_insight sum_monthly_insight_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_monthly_insight
    ADD CONSTRAINT sum_monthly_insight_pkey PRIMARY KEY (summary_id);


--
-- Name: sum_monthly_insight sum_monthly_insight_tenant_id_month_key_merchant_id_store_i_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_monthly_insight
    ADD CONSTRAINT sum_monthly_insight_tenant_id_month_key_merchant_id_store_i_key UNIQUE (tenant_id, month_key, merchant_id, store_id, terminal_id, card_scheme, card_type, destination, channel, is_opt_in);


--
-- Name: sum_monthly_merchant_metrics sum_monthly_merchant_metrics_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_monthly_merchant_metrics
    ADD CONSTRAINT sum_monthly_merchant_metrics_pkey PRIMARY KEY (metric_id);


--
-- Name: sum_monthly_merchant_metrics sum_monthly_merchant_metrics_tenant_id_merchant_id_month_ye_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_monthly_merchant_metrics
    ADD CONSTRAINT sum_monthly_merchant_metrics_tenant_id_merchant_id_month_ye_key UNIQUE (tenant_id, merchant_id, month_year);


--
-- Name: sys_group_menu sys_group_menu_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sys_group_menu
    ADD CONSTRAINT sys_group_menu_pkey PRIMARY KEY (group_id, menu_id);


--
-- Name: sys_menu sys_menu_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sys_menu
    ADD CONSTRAINT sys_menu_pkey PRIMARY KEY (menu_id);


--
-- Name: sys_permission sys_permission_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sys_permission
    ADD CONSTRAINT sys_permission_pkey PRIMARY KEY (perm_code);


--
-- Name: sys_user_group sys_user_group_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sys_user_group
    ADD CONSTRAINT sys_user_group_pkey PRIMARY KEY (group_id);


--
-- Name: tenant tenant_bank_short_code_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tenant
    ADD CONSTRAINT tenant_bank_short_code_key UNIQUE (bank_short_code);


--
-- Name: tenant_group_perm tenant_group_perm_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tenant_group_perm
    ADD CONSTRAINT tenant_group_perm_pkey PRIMARY KEY (tenant_id, group_id, perm_code);


--
-- Name: tenant_group tenant_group_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tenant_group
    ADD CONSTRAINT tenant_group_pkey PRIMARY KEY (tenant_group_id);


--
-- Name: tenant tenant_institution_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tenant
    ADD CONSTRAINT tenant_institution_id_key UNIQUE (institution_id);


--
-- Name: tenant_module tenant_module_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tenant_module
    ADD CONSTRAINT tenant_module_pkey PRIMARY KEY (tenant_id, module_key);


--
-- Name: tenant tenant_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tenant
    ADD CONSTRAINT tenant_pkey PRIMARY KEY (tenant_id);


--
-- Name: tenant_provision_log tenant_provision_log_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tenant_provision_log
    ADD CONSTRAINT tenant_provision_log_pkey PRIMARY KEY (log_id);


--
-- Name: tenant_provision_script tenant_provision_script_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tenant_provision_script
    ADD CONSTRAINT tenant_provision_script_pkey PRIMARY KEY (script_id);


--
-- Name: tenant_provision_script tenant_provision_script_script_name_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tenant_provision_script
    ADD CONSTRAINT tenant_provision_script_script_name_key UNIQUE (script_name);


--
-- Name: tenant_setting tenant_setting_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tenant_setting
    ADD CONSTRAINT tenant_setting_pkey PRIMARY KEY (setting_id);


--
-- Name: tenant_setting tenant_setting_tenant_id_setting_key_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tenant_setting
    ADD CONSTRAINT tenant_setting_tenant_id_setting_key_key UNIQUE (tenant_id, setting_key);


--
-- Name: terminal_channel_map terminal_channel_map_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.terminal_channel_map
    ADD CONSTRAINT terminal_channel_map_pkey PRIMARY KEY (id);


--
-- Name: ticket_size_bucket ticket_size_bucket_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ticket_size_bucket
    ADD CONSTRAINT ticket_size_bucket_pkey PRIMARY KEY (id);


--
-- Name: explorer_master_item uq_master_item; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.explorer_master_item
    ADD CONSTRAINT uq_master_item UNIQUE (tenant_id, item_type, item_key);


--
-- Name: sys_menu uq_menu_path; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sys_menu
    ADD CONSTRAINT uq_menu_path UNIQUE (path);


--
-- Name: merchant_churn_score uq_merchant_churn_score; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.merchant_churn_score
    ADD CONSTRAINT uq_merchant_churn_score UNIQUE (tenant_id, merchant_id, calc_date);


--
-- Name: merchant_segment uq_merchant_segment; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.merchant_segment
    ADD CONSTRAINT uq_merchant_segment UNIQUE (tenant_id, merchant_id, calc_date);


--
-- Name: sales_agent_profile uq_sales_agent_profile_tenant_user; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sales_agent_profile
    ADD CONSTRAINT uq_sales_agent_profile_tenant_user UNIQUE (tenant_id, sales_user_id);


--
-- Name: sales_agent_target uq_sales_agent_target; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sales_agent_target
    ADD CONSTRAINT uq_sales_agent_target UNIQUE (tenant_id, sales_user_id, month_key, metric_type);


--
-- Name: sales_team_mapping uq_sales_team_tenant_email; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sales_team_mapping
    ADD CONSTRAINT uq_sales_team_tenant_email UNIQUE (tenant_id, team_lead_email);


--
-- Name: sales_user_assignment uq_sales_user_tenant; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sales_user_assignment
    ADD CONSTRAINT uq_sales_user_tenant UNIQUE (tenant_id, sales_user_id);


--
-- Name: saved_filter uq_saved_filter_name; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.saved_filter
    ADD CONSTRAINT uq_saved_filter_name UNIQUE (tenant_id, user_id, dashboard_type, name);


--
-- Name: tenant_group uq_tenant_group; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tenant_group
    ADD CONSTRAINT uq_tenant_group UNIQUE (tenant_id, group_id);


--
-- Name: user_region_access user_region_access_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_region_access
    ADD CONSTRAINT user_region_access_pkey PRIMARY KEY (access_id);


--
-- Name: user_region_access user_region_access_user_id_region_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_region_access
    ADD CONSTRAINT user_region_access_user_id_region_id_key UNIQUE (user_id, region_id);


--
-- Name: user_role user_role_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_role
    ADD CONSTRAINT user_role_pkey PRIMARY KEY (map_id);


--
-- Name: user_role user_role_user_id_role_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_role
    ADD CONSTRAINT user_role_user_id_role_id_key UNIQUE (user_id, role_id);


--
-- Name: user_tenant_access user_tenant_access_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_tenant_access
    ADD CONSTRAINT user_tenant_access_pkey PRIMARY KEY (access_id);


--
-- Name: user_tenant_access user_tenant_access_user_id_tenant_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_tenant_access
    ADD CONSTRAINT user_tenant_access_user_id_tenant_id_key UNIQUE (user_id, tenant_id);


--
-- Name: users users_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_pkey PRIMARY KEY (user_id);


--
-- Name: users users_username_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_username_key UNIQUE (username);


--
-- Name: webhook_delivery webhook_delivery_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.webhook_delivery
    ADD CONSTRAINT webhook_delivery_pkey PRIMARY KEY (delivery_id);


--
-- Name: webhook_endpoint webhook_endpoint_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.webhook_endpoint
    ADD CONSTRAINT webhook_endpoint_pkey PRIMARY KEY (endpoint_id);


--
-- Name: idx_fact_txn_merchant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_fact_txn_merchant ON ONLY public.fact_transaction USING btree (merchant_id);


--
-- Name: fact_transaction_default_merchant_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX fact_transaction_default_merchant_id_idx ON public.fact_transaction_default USING btree (merchant_id);


--
-- Name: idx_fact_fee_resolution; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_fact_fee_resolution ON ONLY public.fact_transaction USING btree (tenant_id, fee_resolution_status) WHERE ((fee_resolution_status IS NOT NULL) AND ((fee_resolution_status)::text <> 'RESOLVED'::text));


--
-- Name: fact_transaction_default_tenant_id_fee_resolution_status_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX fact_transaction_default_tenant_id_fee_resolution_status_idx ON public.fact_transaction_default USING btree (tenant_id, fee_resolution_status) WHERE ((fee_resolution_status IS NOT NULL) AND ((fee_resolution_status)::text <> 'RESOLVED'::text));


--
-- Name: idx_fact_txn_tenant_merchant_date; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_fact_txn_tenant_merchant_date ON ONLY public.fact_transaction USING btree (tenant_id, merchant_id, payment_date);


--
-- Name: fact_transaction_default_tenant_id_merchant_id_payment_date_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX fact_transaction_default_tenant_id_merchant_id_payment_date_idx ON public.fact_transaction_default USING btree (tenant_id, merchant_id, payment_date);


--
-- Name: idx_fact_txn_tenant_date; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_fact_txn_tenant_date ON ONLY public.fact_transaction USING btree (tenant_id, payment_date);


--
-- Name: fact_transaction_default_tenant_id_payment_date_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX fact_transaction_default_tenant_id_payment_date_idx ON public.fact_transaction_default USING btree (tenant_id, payment_date);


--
-- Name: idx_ai_chat_tenant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ai_chat_tenant ON public.ai_chat_history USING btree (tenant_id, user_id);


--
-- Name: idx_alert_history_tenant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_alert_history_tenant ON public.alert_history USING btree (tenant_id);


--
-- Name: idx_alert_history_triggered; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_alert_history_triggered ON public.alert_history USING btree (triggered_at DESC);


--
-- Name: idx_alert_rule_tenant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_alert_rule_tenant ON public.alert_rule USING btree (tenant_id);


--
-- Name: idx_api_idempotency_age; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_api_idempotency_age ON public.api_idempotency USING btree (created_at);


--
-- Name: idx_api_key_hash; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_api_key_hash ON public.api_key USING btree (key_hash);


--
-- Name: idx_api_key_prefix_active; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_api_key_prefix_active ON public.api_key USING btree (key_prefix) WHERE (is_active = true);


--
-- Name: idx_api_key_tenant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_api_key_tenant ON public.api_key USING btree (tenant_id);


--
-- Name: idx_api_req_log_tenant_key_time; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_api_req_log_tenant_key_time ON public.api_request_log USING btree (tenant_id, key_id, created_at DESC);


--
-- Name: idx_api_req_log_time; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_api_req_log_time ON public.api_request_log USING btree (created_at DESC);


--
-- Name: idx_churn_score_merchant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_churn_score_merchant ON public.merchant_churn_score USING btree (tenant_id, merchant_id, calc_date DESC);


--
-- Name: idx_churn_score_tenant_date; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_churn_score_tenant_date ON public.merchant_churn_score USING btree (tenant_id, calc_date DESC);


--
-- Name: idx_db_maintenance_run_started; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_db_maintenance_run_started ON public.db_maintenance_run USING btree (started_at DESC);


--
-- Name: idx_destination_token_map_lookup; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_destination_token_map_lookup ON public.destination_token_map USING btree (country_code, raw_token);


--
-- Name: idx_dim_merchant_mid; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_dim_merchant_mid ON public.dim_merchant USING btree (mid, tenant_id);


--
-- Name: idx_dim_merchant_tenant_mid; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_dim_merchant_tenant_mid ON public.dim_merchant USING btree (tenant_id, mid) WHERE (mid IS NOT NULL);


--
-- Name: idx_dim_store_merchant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_dim_store_merchant ON public.dim_store USING btree (merchant_id, tenant_id);


--
-- Name: idx_dim_store_sid; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_dim_store_sid ON public.dim_store USING btree (sid, tenant_id);


--
-- Name: idx_dim_store_tenant_sid; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_dim_store_tenant_sid ON public.dim_store USING btree (tenant_id, sid) WHERE (sid IS NOT NULL);


--
-- Name: idx_dim_terminal_store; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_dim_terminal_store ON public.dim_terminal USING btree (store_id, tenant_id);


--
-- Name: idx_dim_terminal_tenant_store; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_dim_terminal_tenant_store ON public.dim_terminal USING btree (tenant_id, store_id);


--
-- Name: idx_dim_terminal_tenant_tid; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_dim_terminal_tenant_tid ON public.dim_terminal USING btree (tenant_id, tid) WHERE (tid IS NOT NULL);


--
-- Name: idx_dim_terminal_tid; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_dim_terminal_tid ON public.dim_terminal USING btree (tid, tenant_id);


--
-- Name: idx_email_camp_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_email_camp_status ON public.email_campaign USING btree (tenant_id, status);


--
-- Name: idx_email_camp_tenant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_email_camp_tenant ON public.email_campaign USING btree (tenant_id);


--
-- Name: idx_email_clog_campaign; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_email_clog_campaign ON public.email_campaign_log USING btree (campaign_id);


--
-- Name: idx_email_clog_tenant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_email_clog_tenant ON public.email_campaign_log USING btree (tenant_id, sent_at DESC);


--
-- Name: idx_email_queue_pending; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_email_queue_pending ON public.email_queue USING btree (status, created_at) WHERE ((status)::text = 'PENDING'::text);


--
-- Name: idx_email_queue_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_email_queue_status ON public.email_queue USING btree (status);


--
-- Name: idx_email_queue_tenant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_email_queue_tenant ON public.email_queue USING btree (tenant_id);


--
-- Name: idx_email_queue_tenant_month; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_email_queue_tenant_month ON public.email_queue USING btree (tenant_id, statement_month);


--
-- Name: idx_email_smtp_config_tenant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_email_smtp_config_tenant ON public.email_smtp_config USING btree (tenant_id);


--
-- Name: idx_email_tpl_tenant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_email_tpl_tenant ON public.email_template_config USING btree (tenant_id);


--
-- Name: idx_event_outbox_pending; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_event_outbox_pending ON public.event_outbox USING btree (id) WHERE ((status)::text = 'PENDING'::text);


--
-- Name: idx_explorer_alert_enabled; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_explorer_alert_enabled ON public.explorer_alert USING btree (is_enabled);


--
-- Name: idx_explorer_alert_tenant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_explorer_alert_tenant ON public.explorer_alert USING btree (tenant_id);


--
-- Name: idx_fact_dcc_tenant_date; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_fact_dcc_tenant_date ON public.fact_dcc_revenue USING btree (tenant_id, payment_date);


--
-- Name: idx_fact_dcc_tenant_merchant_date; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_fact_dcc_tenant_merchant_date ON public.fact_dcc_revenue USING btree (tenant_id, merchant_id, payment_date);


--
-- Name: idx_fact_rental_tenant_date; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_fact_rental_tenant_date ON public.fact_rental USING btree (tenant_id, payment_date);


--
-- Name: idx_fact_rental_tenant_level_date; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_fact_rental_tenant_level_date ON public.fact_rental USING btree (tenant_id, level, payment_date);


--
-- Name: idx_ic_norm_detail_merchant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ic_norm_detail_merchant ON public.interchange_normalization_detail USING btree (merchant_id);


--
-- Name: idx_ic_norm_detail_run; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ic_norm_detail_run ON public.interchange_normalization_detail USING btree (run_id);


--
-- Name: idx_ic_norm_run_tenant_month; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ic_norm_run_tenant_month ON public.interchange_normalization_run USING btree (tenant_id, month_key);


--
-- Name: idx_int_run_log_report_time; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_int_run_log_report_time ON public.integration_run_log USING btree (report_id, start_time DESC);


--
-- Name: idx_int_run_log_schedule_time; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_int_run_log_schedule_time ON public.integration_run_log USING btree (schedule_id, start_time DESC);


--
-- Name: idx_interchange_rate_local_c_generic; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_interchange_rate_local_c_generic ON public.interchange_rate_local USING btree (country_code, dest) WHERE (mcc IS NULL);


--
-- Name: idx_interchange_rate_local_c_lookup; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_interchange_rate_local_c_lookup ON public.interchange_rate_local USING btree (country_code, dest, priority DESC);


--
-- Name: idx_interchange_rate_local_c_mcc; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_interchange_rate_local_c_mcc ON public.interchange_rate_local USING btree (country_code, mcc) WHERE (mcc IS NOT NULL);


--
-- Name: idx_interchange_rate_local_generic; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_interchange_rate_local_generic ON public.interchange_rate_local USING btree (tenant_id, dest) WHERE (mcc IS NULL);


--
-- Name: idx_interchange_rate_local_lookup; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_interchange_rate_local_lookup ON public.interchange_rate_local USING btree (tenant_id, dest, priority DESC);


--
-- Name: idx_interchange_rate_local_mcc; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_interchange_rate_local_mcc ON public.interchange_rate_local USING btree (tenant_id, mcc) WHERE (mcc IS NOT NULL);


--
-- Name: idx_intg_conn_tenant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_intg_conn_tenant ON public.integration_connection USING btree (tenant_id);


--
-- Name: idx_intg_report_approved; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_intg_report_approved ON public.integration_report USING btree (tenant_id, approved_by);


--
-- Name: idx_intg_report_tenant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_intg_report_tenant ON public.integration_report USING btree (tenant_id);


--
-- Name: idx_intg_report_type; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_intg_report_type ON public.integration_report USING btree (tenant_id, report_type);


--
-- Name: idx_intg_run_report; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_intg_run_report ON public.integration_run_log USING btree (report_id);


--
-- Name: idx_intg_run_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_intg_run_status ON public.integration_run_log USING btree (tenant_id, status);


--
-- Name: idx_intg_run_tenant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_intg_run_tenant ON public.integration_run_log USING btree (tenant_id, start_time DESC);


--
-- Name: idx_intg_sched_enabled; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_intg_sched_enabled ON public.integration_schedule USING btree (is_enabled);


--
-- Name: idx_intg_sched_tenant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_intg_sched_tenant ON public.integration_schedule USING btree (tenant_id);


--
-- Name: idx_login_mfa_ticket; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_login_mfa_ticket ON public.login_mfa_token USING btree (ticket);


--
-- Name: idx_login_mfa_user; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_login_mfa_user ON public.login_mfa_token USING btree (user_id, used);


--
-- Name: idx_master_item_tenant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_master_item_tenant ON public.explorer_master_item USING btree (tenant_id);


--
-- Name: idx_mcc_sector_map_country; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_mcc_sector_map_country ON public.mcc_sector_map USING btree (country_code, mcc);


--
-- Name: idx_mcc_sector_map_lookup; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_mcc_sector_map_lookup ON public.mcc_sector_map USING btree (tenant_id, mcc);


--
-- Name: idx_mdm_tenant_report_date; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_mdm_tenant_report_date ON public.merchant_daily_metrics USING btree (tenant_id, report_date);


--
-- Name: idx_merchant_segment_merchant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_merchant_segment_merchant ON public.merchant_segment USING btree (tenant_id, merchant_id, calc_date DESC);


--
-- Name: idx_merchant_segment_primary; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_merchant_segment_primary ON public.merchant_segment USING btree (tenant_id, calc_date, primary_segment);


--
-- Name: idx_merchant_segment_tenant_date; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_merchant_segment_tenant_date ON public.merchant_segment USING btree (tenant_id, calc_date DESC);


--
-- Name: idx_metrics_date; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_metrics_date ON public.merchant_daily_metrics USING btree (report_date);


--
-- Name: idx_metrics_merchant_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_metrics_merchant_id ON public.merchant_daily_metrics USING btree (merchant_id);


--
-- Name: idx_metrics_mid; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_metrics_mid ON public.merchant_daily_metrics USING btree (mid);


--
-- Name: idx_mpe_dir_file; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_mpe_dir_file ON public.mpe_table_directory USING btree (file_id);


--
-- Name: idx_mpe_record_file_sub; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_mpe_record_file_sub ON public.mpe_record USING btree (file_id, sub_id);


--
-- Name: idx_msah_job; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_msah_job ON public.merchant_sales_assignment_history USING btree (tenant_id, job_execution_id);


--
-- Name: idx_msah_merchant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_msah_merchant ON public.merchant_sales_assignment_history USING btree (tenant_id, merchant_id, changed_at DESC);


--
-- Name: idx_msah_new_agent; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_msah_new_agent ON public.merchant_sales_assignment_history USING btree (tenant_id, new_sales_user_id, changed_at DESC);


--
-- Name: idx_password_history_user; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_password_history_user ON public.password_history USING btree (user_id);


--
-- Name: idx_pdf_batch_log_job; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_pdf_batch_log_job ON public.pdf_batch_log USING btree (job_id);


--
-- Name: idx_pdf_batch_log_month; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_pdf_batch_log_month ON public.pdf_batch_log USING btree (target_month);


--
-- Name: idx_pdf_batch_log_tenant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_pdf_batch_log_tenant ON public.pdf_batch_log USING btree (tenant_id);


--
-- Name: idx_pdf_job_status_tenant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_pdf_job_status_tenant ON public.pdf_job_status USING btree (tenant_id, kind, updated_at);


--
-- Name: idx_profit_adj_lookup; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_profit_adj_lookup ON public.profitability_adjustment_rate USING btree (tenant_id, adj_code, mid);


--
-- Name: idx_provision_log_tenant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_provision_log_tenant ON public.tenant_provision_log USING btree (tenant_id);


--
-- Name: idx_pw_reset_token_user; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_pw_reset_token_user ON public.password_reset_token USING btree (user_id, used);


--
-- Name: idx_ref_bin_country; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ref_bin_country ON public.ref_bin USING btree (issuer_country);


--
-- Name: idx_ref_bin_range_country; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ref_bin_range_country ON public.ref_bin_range USING btree (issuer_country);


--
-- Name: idx_ref_bin_range_lookup; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ref_bin_range_lookup ON public.ref_bin_range USING btree (scheme, range_low, range_high);


--
-- Name: idx_ref_bin_scheme; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ref_bin_scheme ON public.ref_bin USING btree (scheme);


--
-- Name: idx_refresh_token_expires; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_refresh_token_expires ON public.refresh_token USING btree (expires_at) WHERE (revoked = false);


--
-- Name: idx_refresh_token_hash; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_refresh_token_hash ON public.refresh_token USING btree (token_hash);


--
-- Name: idx_refresh_token_username; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_refresh_token_username ON public.refresh_token USING btree (username);


--
-- Name: idx_report_run_log_job; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_report_run_log_job ON public.report_run_log USING btree (job_id);


--
-- Name: idx_report_run_log_tenant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_report_run_log_tenant ON public.report_run_log USING btree (tenant_id);


--
-- Name: idx_report_sched_tenant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_report_sched_tenant ON public.report_schedule USING btree (tenant_id);


--
-- Name: idx_report_tpl_tenant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_report_tpl_tenant ON public.report_template USING btree (tenant_id, user_id);


--
-- Name: idx_reset_token; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_reset_token ON public.password_reset_token USING btree (token);


--
-- Name: idx_sales_agent_profile_tenant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sales_agent_profile_tenant ON public.sales_agent_profile USING btree (tenant_id);


--
-- Name: idx_sales_agent_target_agent; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sales_agent_target_agent ON public.sales_agent_target USING btree (tenant_id, sales_user_id, month_key);


--
-- Name: idx_sales_agent_target_window; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sales_agent_target_window ON public.sales_agent_target USING btree (tenant_id, month_key, sales_user_id);


--
-- Name: idx_sales_assign_lead; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sales_assign_lead ON public.sales_user_assignment USING btree (team_lead_id);


--
-- Name: idx_sales_assign_tenant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sales_assign_tenant ON public.sales_user_assignment USING btree (tenant_id);


--
-- Name: idx_sales_country_lead_tenant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sales_country_lead_tenant ON public.sales_country_lead USING btree (tenant_id);


--
-- Name: idx_sales_team_country_lead; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sales_team_country_lead ON public.sales_team_mapping USING btree (country_lead_id) WHERE (country_lead_id IS NOT NULL);


--
-- Name: idx_sales_team_tenant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sales_team_tenant ON public.sales_team_mapping USING btree (tenant_id);


--
-- Name: idx_saved_filter_lookup; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_saved_filter_lookup ON public.saved_filter USING btree (tenant_id, user_id, dashboard_type);


--
-- Name: idx_scheme_fee_rate_country; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_scheme_fee_rate_country ON public.scheme_fee_rate USING btree (country_code, dest, channel);


--
-- Name: idx_sdi_card_rollup; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sdi_card_rollup ON ONLY public.sum_daily_insight USING btree (tenant_id, business_date, card_scheme, card_type, destination, channel) INCLUDE (total_txns, total_volume, total_msf);


--
-- Name: idx_sdi_merchant_rollup; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sdi_merchant_rollup ON ONLY public.sum_daily_insight USING btree (tenant_id, business_date, merchant_id, store_id) INCLUDE (total_txns, total_volume, total_msf);


--
-- Name: idx_sdi_summary_rollup; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sdi_summary_rollup ON ONLY public.sum_daily_insight USING btree (tenant_id, business_date) INCLUDE (merchant_id, is_opt_in, destination, total_txns, total_volume, total_msf);


--
-- Name: idx_sdm_attrition_rollup; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sdm_attrition_rollup ON ONLY public.sum_daily_merchant USING btree (tenant_id, business_date, merchant_id) INCLUDE (total_txns, total_volume, total_msf);


--
-- Name: idx_sdma_attr_type; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sdma_attr_type ON ONLY public.sum_daily_merchant_attribute USING btree (attribute_type);


--
-- Name: idx_sdma_merchant_date; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sdma_merchant_date ON ONLY public.sum_daily_merchant_attribute USING btree (merchant_id, business_date);


--
-- Name: idx_sdma_tenant_date_merchant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sdma_tenant_date_merchant ON ONLY public.sum_daily_merchant_attribute USING btree (tenant_id, business_date, merchant_id, attribute_type);


--
-- Name: idx_sdt_fee_rollup; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sdt_fee_rollup ON ONLY public.sum_daily_terminal USING btree (tenant_id, business_date, merchant_id, store_id);


--
-- Name: idx_smc_tenant_month_merchant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_smc_tenant_month_merchant ON public.sum_monthly_card USING btree (tenant_id, month_key, merchant_id);


--
-- Name: idx_smi_card_rollup; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_smi_card_rollup ON public.sum_monthly_insight USING btree (tenant_id, month_key, card_scheme, card_type, destination, channel) INCLUDE (total_txns, total_volume, total_msf);


--
-- Name: idx_smi_merchant_rollup; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_smi_merchant_rollup ON public.sum_monthly_insight USING btree (tenant_id, month_key, merchant_id, store_id) INCLUDE (total_txns, total_volume, total_msf);


--
-- Name: idx_smi_summary_rollup; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_smi_summary_rollup ON public.sum_monthly_insight USING btree (tenant_id, month_key) INCLUDE (merchant_id, is_opt_in, destination, total_txns, total_volume, total_msf);


--
-- Name: idx_sso_state_expires; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sso_state_expires ON public.sso_state_token USING btree (expires_at);


--
-- Name: idx_stg_dcc_tenant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_stg_dcc_tenant ON public.stg_dcc_revenue_raw USING btree (tenant_id, status);


--
-- Name: idx_stg_merch_city; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_stg_merch_city ON public.stg_merchant_master_raw USING btree (tenant_id, city);


--
-- Name: idx_stg_merch_industry; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_stg_merch_industry ON public.stg_merchant_master_raw USING btree (tenant_id, industry_type);


--
-- Name: idx_stg_merch_mcc; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_stg_merch_mcc ON public.stg_merchant_master_raw USING btree (tenant_id, business_mcc);


--
-- Name: idx_stg_merch_mid; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_stg_merch_mid ON public.stg_merchant_master_raw USING btree (tenant_id, mid);


--
-- Name: idx_stg_merch_partner; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_stg_merch_partner ON public.stg_merchant_master_raw USING btree (tenant_id, referral_partner);


--
-- Name: idx_stg_merch_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_stg_merch_status ON public.stg_merchant_master_raw USING btree (tenant_id, merchant_status);


--
-- Name: idx_stg_merch_tenant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_stg_merch_tenant ON public.stg_merchant_master_raw USING btree (tenant_id);


--
-- Name: idx_stg_merchant_tenant_loadtime; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_stg_merchant_tenant_loadtime ON public.stg_merchant_master_raw USING btree (tenant_id, load_time);


--
-- Name: idx_stg_mid; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_stg_mid ON public.stg_trnx_raw USING btree (mid);


--
-- Name: idx_stg_rental_tenant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_stg_rental_tenant ON public.stg_rental_raw USING btree (tenant_id, status);


--
-- Name: idx_stg_tenant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_stg_tenant ON public.stg_trnx_raw USING btree (tenant_id);


--
-- Name: idx_stg_tenant_date; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_stg_tenant_date ON public.stg_trnx_raw USING btree (tenant_id, payment_date);


--
-- Name: idx_stg_trnx_tenant_paydate; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_stg_trnx_tenant_paydate ON public.stg_trnx_raw USING btree (tenant_id, payment_date);


--
-- Name: idx_stg_txn_arn_date; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_stg_txn_arn_date ON public.stg_trnx_raw USING btree (tenant_id, arn, payment_date);


--
-- Name: idx_stg_txn_dest; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_stg_txn_dest ON public.stg_trnx_raw USING btree (tenant_id, destination);


--
-- Name: idx_stg_txn_scheme; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_stg_txn_scheme ON public.stg_trnx_raw USING btree (tenant_id, card_scheme);


--
-- Name: idx_stg_txn_type; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_stg_txn_type ON public.stg_trnx_raw USING btree (tenant_id, transaction_type);


--
-- Name: idx_sum_bank_tenant_date; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sum_bank_tenant_date ON ONLY public.sum_daily_bank USING btree (tenant_id, business_date);


--
-- Name: idx_sum_card_merch_month; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sum_card_merch_month ON public.sum_monthly_card USING btree (merchant_id, month_key);


--
-- Name: idx_sum_channel_tenant_date; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sum_channel_tenant_date ON ONLY public.sum_daily_channel USING btree (tenant_id, business_date);


--
-- Name: idx_sum_daily_explorer_tenant_date; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sum_daily_explorer_tenant_date ON ONLY public.sum_daily_explorer USING btree (tenant_id, business_date);


--
-- Name: idx_sum_daily_explorer_tenant_date_merch; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sum_daily_explorer_tenant_date_merch ON ONLY public.sum_daily_explorer USING btree (tenant_id, business_date, merchant_id);


--
-- Name: idx_sum_daily_full_fee_overlay; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sum_daily_full_fee_overlay ON ONLY public.sum_daily_full USING btree (tenant_id, business_date) INCLUDE (destination, card_type, total_msf, total_interchange, total_scheme_fee);


--
-- Name: idx_sum_daily_full_tenant_date; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sum_daily_full_tenant_date ON ONLY public.sum_daily_full USING btree (tenant_id, business_date);


--
-- Name: idx_sum_daily_full_tenant_date_merch; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sum_daily_full_tenant_date_merch ON ONLY public.sum_daily_full USING btree (tenant_id, business_date, merchant_id);


--
-- Name: idx_sum_daily_ldb_tenant_date; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sum_daily_ldb_tenant_date ON ONLY public.sum_daily_local_debit_bin USING btree (tenant_id, business_date);


--
-- Name: idx_sum_daily_ldb_tenant_date_bin; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sum_daily_ldb_tenant_date_bin ON ONLY public.sum_daily_local_debit_bin USING btree (tenant_id, business_date, bin6);


--
-- Name: idx_sum_daily_merchant_tenant_date; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sum_daily_merchant_tenant_date ON ONLY public.sum_daily_merchant USING btree (tenant_id, business_date);


--
-- Name: idx_sum_daily_terminal_tenant_terminal_date; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sum_daily_terminal_tenant_terminal_date ON ONLY public.sum_daily_terminal USING btree (tenant_id, terminal_id, business_date);


--
-- Name: idx_sum_fin_tenant_date; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sum_fin_tenant_date ON ONLY public.sum_daily_finance USING btree (tenant_id, business_date);


--
-- Name: idx_sum_insight_merchant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sum_insight_merchant ON ONLY public.sum_daily_insight USING btree (merchant_id);


--
-- Name: idx_sum_insight_tenant_date; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sum_insight_tenant_date ON ONLY public.sum_daily_insight USING btree (tenant_id, business_date);


--
-- Name: idx_sum_merch_dest_tenant_date_dest; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sum_merch_dest_tenant_date_dest ON ONLY public.sum_daily_merchant_destination USING btree (tenant_id, business_date, destination);


--
-- Name: idx_sum_merch_dest_tenant_date_merch; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sum_merch_dest_tenant_date_merch ON ONLY public.sum_daily_merchant_destination USING btree (tenant_id, business_date, merchant_id);


--
-- Name: idx_sum_merch_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sum_merch_id ON ONLY public.sum_daily_merchant USING btree (merchant_id);


--
-- Name: idx_sum_merch_tenant_date; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sum_merch_tenant_date ON ONLY public.sum_daily_merchant USING btree (tenant_id, business_date);


--
-- Name: idx_sum_scheme_tenant_date; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sum_scheme_tenant_date ON ONLY public.sum_daily_scheme USING btree (tenant_id, business_date);


--
-- Name: idx_sum_term_ids; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sum_term_ids ON ONLY public.sum_daily_terminal USING btree (merchant_id, terminal_id);


--
-- Name: idx_sum_term_tenant_date; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sum_term_tenant_date ON ONLY public.sum_daily_terminal USING btree (tenant_id, business_date);


--
-- Name: idx_sys_menu_module; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sys_menu_module ON public.sys_menu USING btree (module_key);


--
-- Name: idx_sys_permission_menu; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sys_permission_menu ON public.sys_permission USING btree (menu_key);


--
-- Name: idx_tenant_group_tenant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_tenant_group_tenant ON public.tenant_group USING btree (tenant_id);


--
-- Name: idx_tenant_setting_tenant_key; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_tenant_setting_tenant_key ON public.tenant_setting USING btree (tenant_id, setting_key);


--
-- Name: idx_tgp_lookup; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_tgp_lookup ON public.tenant_group_perm USING btree (tenant_id, group_id, effect);


--
-- Name: idx_webhook_delivery_due; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_webhook_delivery_due ON public.webhook_delivery USING btree (status, next_attempt_at);


--
-- Name: idx_webhook_delivery_tenant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_webhook_delivery_tenant ON public.webhook_delivery USING btree (tenant_id, created_at DESC);


--
-- Name: idx_webhook_endpoint_tenant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_webhook_endpoint_tenant ON public.webhook_endpoint USING btree (tenant_id, is_active);


--
-- Name: ix_digest_dispatch_sent_retry; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_digest_dispatch_sent_retry ON public.digest_dispatch USING btree (tenant_id, status, business_date DESC);


--
-- Name: ix_digest_dispatch_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_digest_dispatch_status ON public.digest_dispatch USING btree (status, tenant_id);


--
-- Name: ix_digest_dispatch_tenant_date; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_digest_dispatch_tenant_date ON public.digest_dispatch USING btree (tenant_id, business_date DESC);


--
-- Name: ix_fx_rate_daily_ccy_date; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_fx_rate_daily_ccy_date ON public.fx_rate_daily USING btree (currency_code, rate_date DESC);


--
-- Name: ix_ic_file_tenant_dir; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_ic_file_tenant_dir ON public.interchange_file USING btree (tenant_id, direction, parsed_at DESC);


--
-- Name: ix_ic_file_tenant_sha; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_ic_file_tenant_sha ON public.interchange_file USING btree (tenant_id, file_sha256);


--
-- Name: ix_ic_txn_arn; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_ic_txn_arn ON public.interchange_transaction USING btree (tenant_id, acquirer_ref_number);


--
-- Name: ix_ic_txn_file; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_ic_txn_file ON public.interchange_transaction USING btree (file_id, seq_in_file);


--
-- Name: ix_ic_viol_file; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_ic_viol_file ON public.interchange_violation USING btree (file_id, severity);


--
-- Name: ix_ic_viol_rule; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_ic_viol_rule ON public.interchange_violation USING btree (tenant_id, rule_code);


--
-- Name: ix_ic_viol_txn; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_ic_viol_txn ON public.interchange_violation USING btree (transaction_id);


--
-- Name: ix_ingest_coverage_loaded; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_ingest_coverage_loaded ON public.ingest_day_coverage USING btree (tenant_id, last_loaded_at DESC);


--
-- Name: ix_ingest_run_jobexec; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_ingest_run_jobexec ON public.ingest_run USING btree (job_execution_id);


--
-- Name: ix_ingest_run_sha; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_ingest_run_sha ON public.ingest_run USING btree (tenant_id, file_sha256);


--
-- Name: ix_ingest_run_started; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_ingest_run_started ON public.ingest_run USING btree (started_at DESC);


--
-- Name: ix_ingest_run_tenant_started; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_ingest_run_tenant_started ON public.ingest_run USING btree (tenant_id, started_at DESC);


--
-- Name: ix_ingest_run_tenant_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_ingest_run_tenant_status ON public.ingest_run USING btree (tenant_id, status);


--
-- Name: ix_ingest_stage_name; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_ingest_stage_name ON public.ingest_run_stage USING btree (stage_name, started_at DESC);


--
-- Name: ix_ingest_stage_run; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_ingest_stage_run ON public.ingest_run_stage USING btree (run_id, seq);


--
-- Name: ix_stg_fx_rate_raw_tenant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_stg_fx_rate_raw_tenant ON public.stg_fx_rate_raw USING btree (tenant_id);


--
-- Name: ix_stg_trnx_raw_run; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_stg_trnx_raw_run ON public.stg_trnx_raw USING btree (tenant_id, ingest_run_id);


--
-- Name: ix_stg_visa_clearing_run; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_stg_visa_clearing_run ON public.stg_visa_clearing_raw USING btree (ingest_run_id, record_no);


--
-- Name: sum_daily_bank_default_tenant_id_business_date_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_bank_default_tenant_id_business_date_idx ON public.sum_daily_bank_default USING btree (tenant_id, business_date);


--
-- Name: sum_daily_bank_y2024_tenant_id_business_date_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_bank_y2024_tenant_id_business_date_idx ON public.sum_daily_bank_y2024 USING btree (tenant_id, business_date);


--
-- Name: sum_daily_bank_y2025_tenant_id_business_date_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_bank_y2025_tenant_id_business_date_idx ON public.sum_daily_bank_y2025 USING btree (tenant_id, business_date);


--
-- Name: sum_daily_bank_y2026_tenant_id_business_date_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_bank_y2026_tenant_id_business_date_idx ON public.sum_daily_bank_y2026 USING btree (tenant_id, business_date);


--
-- Name: sum_daily_bank_y2027_tenant_id_business_date_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_bank_y2027_tenant_id_business_date_idx ON public.sum_daily_bank_y2027 USING btree (tenant_id, business_date);


--
-- Name: sum_daily_channel_default_tenant_id_business_date_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_channel_default_tenant_id_business_date_idx ON public.sum_daily_channel_default USING btree (tenant_id, business_date);


--
-- Name: sum_daily_channel_y2024_tenant_id_business_date_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_channel_y2024_tenant_id_business_date_idx ON public.sum_daily_channel_y2024 USING btree (tenant_id, business_date);


--
-- Name: sum_daily_channel_y2025_tenant_id_business_date_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_channel_y2025_tenant_id_business_date_idx ON public.sum_daily_channel_y2025 USING btree (tenant_id, business_date);


--
-- Name: sum_daily_channel_y2026_tenant_id_business_date_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_channel_y2026_tenant_id_business_date_idx ON public.sum_daily_channel_y2026 USING btree (tenant_id, business_date);


--
-- Name: sum_daily_channel_y2027_tenant_id_business_date_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_channel_y2027_tenant_id_business_date_idx ON public.sum_daily_channel_y2027 USING btree (tenant_id, business_date);


--
-- Name: sum_daily_explorer_default_tenant_id_business_date_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_explorer_default_tenant_id_business_date_idx ON public.sum_daily_explorer_default USING btree (tenant_id, business_date);


--
-- Name: sum_daily_explorer_default_tenant_id_business_date_merchant_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_explorer_default_tenant_id_business_date_merchant_idx ON public.sum_daily_explorer_default USING btree (tenant_id, business_date, merchant_id);


--
-- Name: sum_daily_explorer_y2024_tenant_id_business_date_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_explorer_y2024_tenant_id_business_date_idx ON public.sum_daily_explorer_y2024 USING btree (tenant_id, business_date);


--
-- Name: sum_daily_explorer_y2024_tenant_id_business_date_merchant_i_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_explorer_y2024_tenant_id_business_date_merchant_i_idx ON public.sum_daily_explorer_y2024 USING btree (tenant_id, business_date, merchant_id);


--
-- Name: sum_daily_explorer_y2025_tenant_id_business_date_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_explorer_y2025_tenant_id_business_date_idx ON public.sum_daily_explorer_y2025 USING btree (tenant_id, business_date);


--
-- Name: sum_daily_explorer_y2025_tenant_id_business_date_merchant_i_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_explorer_y2025_tenant_id_business_date_merchant_i_idx ON public.sum_daily_explorer_y2025 USING btree (tenant_id, business_date, merchant_id);


--
-- Name: sum_daily_explorer_y2026_tenant_id_business_date_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_explorer_y2026_tenant_id_business_date_idx ON public.sum_daily_explorer_y2026 USING btree (tenant_id, business_date);


--
-- Name: sum_daily_explorer_y2026_tenant_id_business_date_merchant_i_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_explorer_y2026_tenant_id_business_date_merchant_i_idx ON public.sum_daily_explorer_y2026 USING btree (tenant_id, business_date, merchant_id);


--
-- Name: sum_daily_explorer_y2027_tenant_id_business_date_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_explorer_y2027_tenant_id_business_date_idx ON public.sum_daily_explorer_y2027 USING btree (tenant_id, business_date);


--
-- Name: sum_daily_explorer_y2027_tenant_id_business_date_merchant_i_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_explorer_y2027_tenant_id_business_date_merchant_i_idx ON public.sum_daily_explorer_y2027 USING btree (tenant_id, business_date, merchant_id);


--
-- Name: sum_daily_finance_default_tenant_id_business_date_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_finance_default_tenant_id_business_date_idx ON public.sum_daily_finance_default USING btree (tenant_id, business_date);


--
-- Name: sum_daily_finance_y2024_tenant_id_business_date_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_finance_y2024_tenant_id_business_date_idx ON public.sum_daily_finance_y2024 USING btree (tenant_id, business_date);


--
-- Name: sum_daily_finance_y2025_tenant_id_business_date_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_finance_y2025_tenant_id_business_date_idx ON public.sum_daily_finance_y2025 USING btree (tenant_id, business_date);


--
-- Name: sum_daily_finance_y2026_tenant_id_business_date_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_finance_y2026_tenant_id_business_date_idx ON public.sum_daily_finance_y2026 USING btree (tenant_id, business_date);


--
-- Name: sum_daily_finance_y2027_tenant_id_business_date_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_finance_y2027_tenant_id_business_date_idx ON public.sum_daily_finance_y2027 USING btree (tenant_id, business_date);


--
-- Name: sum_daily_full_default_tenant_id_business_date_destination__idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_full_default_tenant_id_business_date_destination__idx ON public.sum_daily_full_default USING btree (tenant_id, business_date) INCLUDE (destination, card_type, total_msf, total_interchange, total_scheme_fee);


--
-- Name: sum_daily_full_default_tenant_id_business_date_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_full_default_tenant_id_business_date_idx ON public.sum_daily_full_default USING btree (tenant_id, business_date);


--
-- Name: sum_daily_full_default_tenant_id_business_date_merchant_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_full_default_tenant_id_business_date_merchant_id_idx ON public.sum_daily_full_default USING btree (tenant_id, business_date, merchant_id);


--
-- Name: sum_daily_full_y2024_tenant_id_business_date_destination_ca_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_full_y2024_tenant_id_business_date_destination_ca_idx ON public.sum_daily_full_y2024 USING btree (tenant_id, business_date) INCLUDE (destination, card_type, total_msf, total_interchange, total_scheme_fee);


--
-- Name: sum_daily_full_y2024_tenant_id_business_date_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_full_y2024_tenant_id_business_date_idx ON public.sum_daily_full_y2024 USING btree (tenant_id, business_date);


--
-- Name: sum_daily_full_y2024_tenant_id_business_date_merchant_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_full_y2024_tenant_id_business_date_merchant_id_idx ON public.sum_daily_full_y2024 USING btree (tenant_id, business_date, merchant_id);


--
-- Name: sum_daily_full_y2025_tenant_id_business_date_destination_ca_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_full_y2025_tenant_id_business_date_destination_ca_idx ON public.sum_daily_full_y2025 USING btree (tenant_id, business_date) INCLUDE (destination, card_type, total_msf, total_interchange, total_scheme_fee);


--
-- Name: sum_daily_full_y2025_tenant_id_business_date_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_full_y2025_tenant_id_business_date_idx ON public.sum_daily_full_y2025 USING btree (tenant_id, business_date);


--
-- Name: sum_daily_full_y2025_tenant_id_business_date_merchant_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_full_y2025_tenant_id_business_date_merchant_id_idx ON public.sum_daily_full_y2025 USING btree (tenant_id, business_date, merchant_id);


--
-- Name: sum_daily_full_y2026_tenant_id_business_date_destination_ca_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_full_y2026_tenant_id_business_date_destination_ca_idx ON public.sum_daily_full_y2026 USING btree (tenant_id, business_date) INCLUDE (destination, card_type, total_msf, total_interchange, total_scheme_fee);


--
-- Name: sum_daily_full_y2026_tenant_id_business_date_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_full_y2026_tenant_id_business_date_idx ON public.sum_daily_full_y2026 USING btree (tenant_id, business_date);


--
-- Name: sum_daily_full_y2026_tenant_id_business_date_merchant_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_full_y2026_tenant_id_business_date_merchant_id_idx ON public.sum_daily_full_y2026 USING btree (tenant_id, business_date, merchant_id);


--
-- Name: sum_daily_full_y2027_tenant_id_business_date_destination_ca_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_full_y2027_tenant_id_business_date_destination_ca_idx ON public.sum_daily_full_y2027 USING btree (tenant_id, business_date) INCLUDE (destination, card_type, total_msf, total_interchange, total_scheme_fee);


--
-- Name: sum_daily_full_y2027_tenant_id_business_date_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_full_y2027_tenant_id_business_date_idx ON public.sum_daily_full_y2027 USING btree (tenant_id, business_date);


--
-- Name: sum_daily_full_y2027_tenant_id_business_date_merchant_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_full_y2027_tenant_id_business_date_merchant_id_idx ON public.sum_daily_full_y2027 USING btree (tenant_id, business_date, merchant_id);


--
-- Name: sum_daily_insight_default_merchant_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_insight_default_merchant_id_idx ON public.sum_daily_insight_default USING btree (merchant_id);


--
-- Name: sum_daily_insight_default_tenant_id_business_date_card_sche_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_insight_default_tenant_id_business_date_card_sche_idx ON public.sum_daily_insight_default USING btree (tenant_id, business_date, card_scheme, card_type, destination, channel) INCLUDE (total_txns, total_volume, total_msf);


--
-- Name: sum_daily_insight_default_tenant_id_business_date_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_insight_default_tenant_id_business_date_idx ON public.sum_daily_insight_default USING btree (tenant_id, business_date);


--
-- Name: sum_daily_insight_default_tenant_id_business_date_merchant__idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_insight_default_tenant_id_business_date_merchant__idx ON public.sum_daily_insight_default USING btree (tenant_id, business_date, merchant_id, store_id) INCLUDE (total_txns, total_volume, total_msf);


--
-- Name: sum_daily_insight_default_tenant_id_business_date_merchant_idx1; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_insight_default_tenant_id_business_date_merchant_idx1 ON public.sum_daily_insight_default USING btree (tenant_id, business_date) INCLUDE (merchant_id, is_opt_in, destination, total_txns, total_volume, total_msf);


--
-- Name: sum_daily_insight_y2024_merchant_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_insight_y2024_merchant_id_idx ON public.sum_daily_insight_y2024 USING btree (merchant_id);


--
-- Name: sum_daily_insight_y2024_tenant_id_business_date_card_scheme_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_insight_y2024_tenant_id_business_date_card_scheme_idx ON public.sum_daily_insight_y2024 USING btree (tenant_id, business_date, card_scheme, card_type, destination, channel) INCLUDE (total_txns, total_volume, total_msf);


--
-- Name: sum_daily_insight_y2024_tenant_id_business_date_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_insight_y2024_tenant_id_business_date_idx ON public.sum_daily_insight_y2024 USING btree (tenant_id, business_date);


--
-- Name: sum_daily_insight_y2024_tenant_id_business_date_merchant_i_idx1; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_insight_y2024_tenant_id_business_date_merchant_i_idx1 ON public.sum_daily_insight_y2024 USING btree (tenant_id, business_date) INCLUDE (merchant_id, is_opt_in, destination, total_txns, total_volume, total_msf);


--
-- Name: sum_daily_insight_y2024_tenant_id_business_date_merchant_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_insight_y2024_tenant_id_business_date_merchant_id_idx ON public.sum_daily_insight_y2024 USING btree (tenant_id, business_date, merchant_id, store_id) INCLUDE (total_txns, total_volume, total_msf);


--
-- Name: sum_daily_insight_y2025_merchant_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_insight_y2025_merchant_id_idx ON public.sum_daily_insight_y2025 USING btree (merchant_id);


--
-- Name: sum_daily_insight_y2025_tenant_id_business_date_card_scheme_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_insight_y2025_tenant_id_business_date_card_scheme_idx ON public.sum_daily_insight_y2025 USING btree (tenant_id, business_date, card_scheme, card_type, destination, channel) INCLUDE (total_txns, total_volume, total_msf);


--
-- Name: sum_daily_insight_y2025_tenant_id_business_date_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_insight_y2025_tenant_id_business_date_idx ON public.sum_daily_insight_y2025 USING btree (tenant_id, business_date);


--
-- Name: sum_daily_insight_y2025_tenant_id_business_date_merchant_i_idx1; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_insight_y2025_tenant_id_business_date_merchant_i_idx1 ON public.sum_daily_insight_y2025 USING btree (tenant_id, business_date) INCLUDE (merchant_id, is_opt_in, destination, total_txns, total_volume, total_msf);


--
-- Name: sum_daily_insight_y2025_tenant_id_business_date_merchant_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_insight_y2025_tenant_id_business_date_merchant_id_idx ON public.sum_daily_insight_y2025 USING btree (tenant_id, business_date, merchant_id, store_id) INCLUDE (total_txns, total_volume, total_msf);


--
-- Name: sum_daily_insight_y2026_merchant_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_insight_y2026_merchant_id_idx ON public.sum_daily_insight_y2026 USING btree (merchant_id);


--
-- Name: sum_daily_insight_y2026_tenant_id_business_date_card_scheme_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_insight_y2026_tenant_id_business_date_card_scheme_idx ON public.sum_daily_insight_y2026 USING btree (tenant_id, business_date, card_scheme, card_type, destination, channel) INCLUDE (total_txns, total_volume, total_msf);


--
-- Name: sum_daily_insight_y2026_tenant_id_business_date_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_insight_y2026_tenant_id_business_date_idx ON public.sum_daily_insight_y2026 USING btree (tenant_id, business_date);


--
-- Name: sum_daily_insight_y2026_tenant_id_business_date_merchant_i_idx1; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_insight_y2026_tenant_id_business_date_merchant_i_idx1 ON public.sum_daily_insight_y2026 USING btree (tenant_id, business_date) INCLUDE (merchant_id, is_opt_in, destination, total_txns, total_volume, total_msf);


--
-- Name: sum_daily_insight_y2026_tenant_id_business_date_merchant_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_insight_y2026_tenant_id_business_date_merchant_id_idx ON public.sum_daily_insight_y2026 USING btree (tenant_id, business_date, merchant_id, store_id) INCLUDE (total_txns, total_volume, total_msf);


--
-- Name: sum_daily_insight_y2027_merchant_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_insight_y2027_merchant_id_idx ON public.sum_daily_insight_y2027 USING btree (merchant_id);


--
-- Name: sum_daily_insight_y2027_tenant_id_business_date_card_scheme_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_insight_y2027_tenant_id_business_date_card_scheme_idx ON public.sum_daily_insight_y2027 USING btree (tenant_id, business_date, card_scheme, card_type, destination, channel) INCLUDE (total_txns, total_volume, total_msf);


--
-- Name: sum_daily_insight_y2027_tenant_id_business_date_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_insight_y2027_tenant_id_business_date_idx ON public.sum_daily_insight_y2027 USING btree (tenant_id, business_date);


--
-- Name: sum_daily_insight_y2027_tenant_id_business_date_merchant_i_idx1; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_insight_y2027_tenant_id_business_date_merchant_i_idx1 ON public.sum_daily_insight_y2027 USING btree (tenant_id, business_date) INCLUDE (merchant_id, is_opt_in, destination, total_txns, total_volume, total_msf);


--
-- Name: sum_daily_insight_y2027_tenant_id_business_date_merchant_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_insight_y2027_tenant_id_business_date_merchant_id_idx ON public.sum_daily_insight_y2027 USING btree (tenant_id, business_date, merchant_id, store_id) INCLUDE (total_txns, total_volume, total_msf);


--
-- Name: sum_daily_local_debit_bin_defa_tenant_id_business_date_bin6_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_local_debit_bin_defa_tenant_id_business_date_bin6_idx ON public.sum_daily_local_debit_bin_default USING btree (tenant_id, business_date, bin6);


--
-- Name: sum_daily_local_debit_bin_default_tenant_id_business_date_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_local_debit_bin_default_tenant_id_business_date_idx ON public.sum_daily_local_debit_bin_default USING btree (tenant_id, business_date);


--
-- Name: sum_daily_local_debit_bin_y2024_tenant_id_business_date_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_local_debit_bin_y2024_tenant_id_business_date_idx ON public.sum_daily_local_debit_bin_y2024 USING btree (tenant_id, business_date);


--
-- Name: sum_daily_local_debit_bin_y2025_tenant_id_business_date_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_local_debit_bin_y2025_tenant_id_business_date_idx ON public.sum_daily_local_debit_bin_y2025 USING btree (tenant_id, business_date);


--
-- Name: sum_daily_local_debit_bin_y2026_tenant_id_business_date_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_local_debit_bin_y2026_tenant_id_business_date_idx ON public.sum_daily_local_debit_bin_y2026 USING btree (tenant_id, business_date);


--
-- Name: sum_daily_local_debit_bin_y2027_tenant_id_business_date_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_local_debit_bin_y2027_tenant_id_business_date_idx ON public.sum_daily_local_debit_bin_y2027 USING btree (tenant_id, business_date);


--
-- Name: sum_daily_local_debit_bin_y202_tenant_id_business_date_bin6_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_local_debit_bin_y202_tenant_id_business_date_bin6_idx ON public.sum_daily_local_debit_bin_y2024 USING btree (tenant_id, business_date, bin6);


--
-- Name: sum_daily_local_debit_bin_y20_tenant_id_business_date_bin6_idx1; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_local_debit_bin_y20_tenant_id_business_date_bin6_idx1 ON public.sum_daily_local_debit_bin_y2025 USING btree (tenant_id, business_date, bin6);


--
-- Name: sum_daily_local_debit_bin_y20_tenant_id_business_date_bin6_idx2; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_local_debit_bin_y20_tenant_id_business_date_bin6_idx2 ON public.sum_daily_local_debit_bin_y2026 USING btree (tenant_id, business_date, bin6);


--
-- Name: sum_daily_local_debit_bin_y20_tenant_id_business_date_bin6_idx3; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_local_debit_bin_y20_tenant_id_business_date_bin6_idx3 ON public.sum_daily_local_debit_bin_y2027 USING btree (tenant_id, business_date, bin6);


--
-- Name: sum_daily_merch_attr_default_attribute_type_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_merch_attr_default_attribute_type_idx ON public.sum_daily_merch_attr_default USING btree (attribute_type);


--
-- Name: sum_daily_merch_attr_default_merchant_id_business_date_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_merch_attr_default_merchant_id_business_date_idx ON public.sum_daily_merch_attr_default USING btree (merchant_id, business_date);


--
-- Name: sum_daily_merch_attr_default_tenant_id_business_date_mercha_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_merch_attr_default_tenant_id_business_date_mercha_idx ON public.sum_daily_merch_attr_default USING btree (tenant_id, business_date, merchant_id, attribute_type);


--
-- Name: sum_daily_merch_attr_y2024_attribute_type_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_merch_attr_y2024_attribute_type_idx ON public.sum_daily_merch_attr_y2024 USING btree (attribute_type);


--
-- Name: sum_daily_merch_attr_y2024_merchant_id_business_date_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_merch_attr_y2024_merchant_id_business_date_idx ON public.sum_daily_merch_attr_y2024 USING btree (merchant_id, business_date);


--
-- Name: sum_daily_merch_attr_y2024_tenant_id_business_date_merchant_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_merch_attr_y2024_tenant_id_business_date_merchant_idx ON public.sum_daily_merch_attr_y2024 USING btree (tenant_id, business_date, merchant_id, attribute_type);


--
-- Name: sum_daily_merch_attr_y2025_attribute_type_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_merch_attr_y2025_attribute_type_idx ON public.sum_daily_merch_attr_y2025 USING btree (attribute_type);


--
-- Name: sum_daily_merch_attr_y2025_merchant_id_business_date_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_merch_attr_y2025_merchant_id_business_date_idx ON public.sum_daily_merch_attr_y2025 USING btree (merchant_id, business_date);


--
-- Name: sum_daily_merch_attr_y2025_tenant_id_business_date_merchant_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_merch_attr_y2025_tenant_id_business_date_merchant_idx ON public.sum_daily_merch_attr_y2025 USING btree (tenant_id, business_date, merchant_id, attribute_type);


--
-- Name: sum_daily_merch_attr_y2026_attribute_type_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_merch_attr_y2026_attribute_type_idx ON public.sum_daily_merch_attr_y2026 USING btree (attribute_type);


--
-- Name: sum_daily_merch_attr_y2026_merchant_id_business_date_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_merch_attr_y2026_merchant_id_business_date_idx ON public.sum_daily_merch_attr_y2026 USING btree (merchant_id, business_date);


--
-- Name: sum_daily_merch_attr_y2026_tenant_id_business_date_merchant_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_merch_attr_y2026_tenant_id_business_date_merchant_idx ON public.sum_daily_merch_attr_y2026 USING btree (tenant_id, business_date, merchant_id, attribute_type);


--
-- Name: sum_daily_merchant_default_merchant_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_merchant_default_merchant_id_idx ON public.sum_daily_merchant_default USING btree (merchant_id);


--
-- Name: sum_daily_merchant_default_tenant_id_business_date_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_merchant_default_tenant_id_business_date_idx ON public.sum_daily_merchant_default USING btree (tenant_id, business_date);


--
-- Name: sum_daily_merchant_default_tenant_id_business_date_idx1; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_merchant_default_tenant_id_business_date_idx1 ON public.sum_daily_merchant_default USING btree (tenant_id, business_date);


--
-- Name: sum_daily_merchant_default_tenant_id_business_date_merchant_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_merchant_default_tenant_id_business_date_merchant_idx ON public.sum_daily_merchant_default USING btree (tenant_id, business_date, merchant_id) INCLUDE (total_txns, total_volume, total_msf);


--
-- Name: sum_daily_merchant_destinatio_tenant_id_business_date_dest_idx1; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_merchant_destinatio_tenant_id_business_date_dest_idx1 ON public.sum_daily_merchant_destination_y2025 USING btree (tenant_id, business_date, destination);


--
-- Name: sum_daily_merchant_destinatio_tenant_id_business_date_dest_idx2; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_merchant_destinatio_tenant_id_business_date_dest_idx2 ON public.sum_daily_merchant_destination_y2026 USING btree (tenant_id, business_date, destination);


--
-- Name: sum_daily_merchant_destinatio_tenant_id_business_date_dest_idx3; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_merchant_destinatio_tenant_id_business_date_dest_idx3 ON public.sum_daily_merchant_destination_y2027 USING btree (tenant_id, business_date, destination);


--
-- Name: sum_daily_merchant_destinatio_tenant_id_business_date_dest_idx4; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_merchant_destinatio_tenant_id_business_date_dest_idx4 ON public.sum_daily_merchant_destination_default USING btree (tenant_id, business_date, destination);


--
-- Name: sum_daily_merchant_destinatio_tenant_id_business_date_desti_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_merchant_destinatio_tenant_id_business_date_desti_idx ON public.sum_daily_merchant_destination_y2024 USING btree (tenant_id, business_date, destination);


--
-- Name: sum_daily_merchant_destinatio_tenant_id_business_date_merc_idx1; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_merchant_destinatio_tenant_id_business_date_merc_idx1 ON public.sum_daily_merchant_destination_y2025 USING btree (tenant_id, business_date, merchant_id);


--
-- Name: sum_daily_merchant_destinatio_tenant_id_business_date_merc_idx2; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_merchant_destinatio_tenant_id_business_date_merc_idx2 ON public.sum_daily_merchant_destination_y2026 USING btree (tenant_id, business_date, merchant_id);


--
-- Name: sum_daily_merchant_destinatio_tenant_id_business_date_merc_idx3; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_merchant_destinatio_tenant_id_business_date_merc_idx3 ON public.sum_daily_merchant_destination_y2027 USING btree (tenant_id, business_date, merchant_id);


--
-- Name: sum_daily_merchant_destinatio_tenant_id_business_date_merc_idx4; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_merchant_destinatio_tenant_id_business_date_merc_idx4 ON public.sum_daily_merchant_destination_default USING btree (tenant_id, business_date, merchant_id);


--
-- Name: sum_daily_merchant_destinatio_tenant_id_business_date_merch_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_merchant_destinatio_tenant_id_business_date_merch_idx ON public.sum_daily_merchant_destination_y2024 USING btree (tenant_id, business_date, merchant_id);


--
-- Name: sum_daily_merchant_y2024_merchant_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_merchant_y2024_merchant_id_idx ON public.sum_daily_merchant_y2024 USING btree (merchant_id);


--
-- Name: sum_daily_merchant_y2024_tenant_id_business_date_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_merchant_y2024_tenant_id_business_date_idx ON public.sum_daily_merchant_y2024 USING btree (tenant_id, business_date);


--
-- Name: sum_daily_merchant_y2024_tenant_id_business_date_idx1; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_merchant_y2024_tenant_id_business_date_idx1 ON public.sum_daily_merchant_y2024 USING btree (tenant_id, business_date);


--
-- Name: sum_daily_merchant_y2024_tenant_id_business_date_merchant_i_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_merchant_y2024_tenant_id_business_date_merchant_i_idx ON public.sum_daily_merchant_y2024 USING btree (tenant_id, business_date, merchant_id) INCLUDE (total_txns, total_volume, total_msf);


--
-- Name: sum_daily_merchant_y2025_merchant_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_merchant_y2025_merchant_id_idx ON public.sum_daily_merchant_y2025 USING btree (merchant_id);


--
-- Name: sum_daily_merchant_y2025_tenant_id_business_date_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_merchant_y2025_tenant_id_business_date_idx ON public.sum_daily_merchant_y2025 USING btree (tenant_id, business_date);


--
-- Name: sum_daily_merchant_y2025_tenant_id_business_date_idx1; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_merchant_y2025_tenant_id_business_date_idx1 ON public.sum_daily_merchant_y2025 USING btree (tenant_id, business_date);


--
-- Name: sum_daily_merchant_y2025_tenant_id_business_date_merchant_i_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_merchant_y2025_tenant_id_business_date_merchant_i_idx ON public.sum_daily_merchant_y2025 USING btree (tenant_id, business_date, merchant_id) INCLUDE (total_txns, total_volume, total_msf);


--
-- Name: sum_daily_merchant_y2026_merchant_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_merchant_y2026_merchant_id_idx ON public.sum_daily_merchant_y2026 USING btree (merchant_id);


--
-- Name: sum_daily_merchant_y2026_tenant_id_business_date_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_merchant_y2026_tenant_id_business_date_idx ON public.sum_daily_merchant_y2026 USING btree (tenant_id, business_date);


--
-- Name: sum_daily_merchant_y2026_tenant_id_business_date_idx1; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_merchant_y2026_tenant_id_business_date_idx1 ON public.sum_daily_merchant_y2026 USING btree (tenant_id, business_date);


--
-- Name: sum_daily_merchant_y2026_tenant_id_business_date_merchant_i_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_merchant_y2026_tenant_id_business_date_merchant_i_idx ON public.sum_daily_merchant_y2026 USING btree (tenant_id, business_date, merchant_id) INCLUDE (total_txns, total_volume, total_msf);


--
-- Name: sum_daily_scheme_default_tenant_id_business_date_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_scheme_default_tenant_id_business_date_idx ON public.sum_daily_scheme_default USING btree (tenant_id, business_date);


--
-- Name: sum_daily_scheme_y2024_tenant_id_business_date_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_scheme_y2024_tenant_id_business_date_idx ON public.sum_daily_scheme_y2024 USING btree (tenant_id, business_date);


--
-- Name: sum_daily_scheme_y2025_tenant_id_business_date_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_scheme_y2025_tenant_id_business_date_idx ON public.sum_daily_scheme_y2025 USING btree (tenant_id, business_date);


--
-- Name: sum_daily_scheme_y2026_tenant_id_business_date_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_scheme_y2026_tenant_id_business_date_idx ON public.sum_daily_scheme_y2026 USING btree (tenant_id, business_date);


--
-- Name: sum_daily_scheme_y2027_tenant_id_business_date_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_scheme_y2027_tenant_id_business_date_idx ON public.sum_daily_scheme_y2027 USING btree (tenant_id, business_date);


--
-- Name: sum_daily_terminal_default_merchant_id_terminal_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_terminal_default_merchant_id_terminal_id_idx ON public.sum_daily_terminal_default USING btree (merchant_id, terminal_id);


--
-- Name: sum_daily_terminal_default_tenant_id_business_date_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_terminal_default_tenant_id_business_date_idx ON public.sum_daily_terminal_default USING btree (tenant_id, business_date);


--
-- Name: sum_daily_terminal_grain_key; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX sum_daily_terminal_grain_key ON ONLY public.sum_daily_terminal USING btree (tenant_id, business_date, merchant_id, store_id, terminal_id) NULLS NOT DISTINCT;


--
-- Name: sum_daily_terminal_default_tenant_id_business_date_merchan_idx1; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX sum_daily_terminal_default_tenant_id_business_date_merchan_idx1 ON public.sum_daily_terminal_default USING btree (tenant_id, business_date, merchant_id, store_id, terminal_id) NULLS NOT DISTINCT;


--
-- Name: sum_daily_terminal_default_tenant_id_business_date_merchant_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_terminal_default_tenant_id_business_date_merchant_idx ON public.sum_daily_terminal_default USING btree (tenant_id, business_date, merchant_id, store_id);


--
-- Name: sum_daily_terminal_default_tenant_id_terminal_id_business_d_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_terminal_default_tenant_id_terminal_id_business_d_idx ON public.sum_daily_terminal_default USING btree (tenant_id, terminal_id, business_date);


--
-- Name: sum_daily_terminal_y2024_merchant_id_terminal_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_terminal_y2024_merchant_id_terminal_id_idx ON public.sum_daily_terminal_y2024 USING btree (merchant_id, terminal_id);


--
-- Name: sum_daily_terminal_y2024_tenant_id_business_date_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_terminal_y2024_tenant_id_business_date_idx ON public.sum_daily_terminal_y2024 USING btree (tenant_id, business_date);


--
-- Name: sum_daily_terminal_y2024_tenant_id_business_date_merchant__idx1; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX sum_daily_terminal_y2024_tenant_id_business_date_merchant__idx1 ON public.sum_daily_terminal_y2024 USING btree (tenant_id, business_date, merchant_id, store_id, terminal_id) NULLS NOT DISTINCT;


--
-- Name: sum_daily_terminal_y2024_tenant_id_business_date_merchant_i_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_terminal_y2024_tenant_id_business_date_merchant_i_idx ON public.sum_daily_terminal_y2024 USING btree (tenant_id, business_date, merchant_id, store_id);


--
-- Name: sum_daily_terminal_y2024_tenant_id_terminal_id_business_dat_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_terminal_y2024_tenant_id_terminal_id_business_dat_idx ON public.sum_daily_terminal_y2024 USING btree (tenant_id, terminal_id, business_date);


--
-- Name: sum_daily_terminal_y2025_merchant_id_terminal_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_terminal_y2025_merchant_id_terminal_id_idx ON public.sum_daily_terminal_y2025 USING btree (merchant_id, terminal_id);


--
-- Name: sum_daily_terminal_y2025_tenant_id_business_date_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_terminal_y2025_tenant_id_business_date_idx ON public.sum_daily_terminal_y2025 USING btree (tenant_id, business_date);


--
-- Name: sum_daily_terminal_y2025_tenant_id_business_date_merchant__idx1; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX sum_daily_terminal_y2025_tenant_id_business_date_merchant__idx1 ON public.sum_daily_terminal_y2025 USING btree (tenant_id, business_date, merchant_id, store_id, terminal_id) NULLS NOT DISTINCT;


--
-- Name: sum_daily_terminal_y2025_tenant_id_business_date_merchant_i_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_terminal_y2025_tenant_id_business_date_merchant_i_idx ON public.sum_daily_terminal_y2025 USING btree (tenant_id, business_date, merchant_id, store_id);


--
-- Name: sum_daily_terminal_y2025_tenant_id_terminal_id_business_dat_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_terminal_y2025_tenant_id_terminal_id_business_dat_idx ON public.sum_daily_terminal_y2025 USING btree (tenant_id, terminal_id, business_date);


--
-- Name: sum_daily_terminal_y2026_merchant_id_terminal_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_terminal_y2026_merchant_id_terminal_id_idx ON public.sum_daily_terminal_y2026 USING btree (merchant_id, terminal_id);


--
-- Name: sum_daily_terminal_y2026_tenant_id_business_date_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_terminal_y2026_tenant_id_business_date_idx ON public.sum_daily_terminal_y2026 USING btree (tenant_id, business_date);


--
-- Name: sum_daily_terminal_y2026_tenant_id_business_date_merchant__idx1; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX sum_daily_terminal_y2026_tenant_id_business_date_merchant__idx1 ON public.sum_daily_terminal_y2026 USING btree (tenant_id, business_date, merchant_id, store_id, terminal_id) NULLS NOT DISTINCT;


--
-- Name: sum_daily_terminal_y2026_tenant_id_business_date_merchant_i_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_terminal_y2026_tenant_id_business_date_merchant_i_idx ON public.sum_daily_terminal_y2026 USING btree (tenant_id, business_date, merchant_id, store_id);


--
-- Name: sum_daily_terminal_y2026_tenant_id_terminal_id_business_dat_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_terminal_y2026_tenant_id_terminal_id_business_dat_idx ON public.sum_daily_terminal_y2026 USING btree (tenant_id, terminal_id, business_date);


--
-- Name: sum_daily_terminal_y2027_merchant_id_terminal_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_terminal_y2027_merchant_id_terminal_id_idx ON public.sum_daily_terminal_y2027 USING btree (merchant_id, terminal_id);


--
-- Name: sum_daily_terminal_y2027_tenant_id_business_date_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_terminal_y2027_tenant_id_business_date_idx ON public.sum_daily_terminal_y2027 USING btree (tenant_id, business_date);


--
-- Name: sum_daily_terminal_y2027_tenant_id_business_date_merchant__idx1; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX sum_daily_terminal_y2027_tenant_id_business_date_merchant__idx1 ON public.sum_daily_terminal_y2027 USING btree (tenant_id, business_date, merchant_id, store_id, terminal_id) NULLS NOT DISTINCT;


--
-- Name: sum_daily_terminal_y2027_tenant_id_business_date_merchant_i_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_terminal_y2027_tenant_id_business_date_merchant_i_idx ON public.sum_daily_terminal_y2027 USING btree (tenant_id, business_date, merchant_id, store_id);


--
-- Name: sum_daily_terminal_y2027_tenant_id_terminal_id_business_dat_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX sum_daily_terminal_y2027_tenant_id_terminal_id_business_dat_idx ON public.sum_daily_terminal_y2027 USING btree (tenant_id, terminal_id, business_date);


--
-- Name: uq_destination_token_map; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_destination_token_map ON public.destination_token_map USING btree (country_code, COALESCE(tenant_id, 0), COALESCE(feed_code, '*'::character varying), raw_token);


--
-- Name: uq_ecom_flat_fee_gateway_key; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_ecom_flat_fee_gateway_key ON public.ecom_flat_fee USING btree (country_code, COALESCE(tenant_id, 0), COALESCE(gateway_type, '*'::character varying));


--
-- Name: uq_ecom_flat_fee_one_default; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_ecom_flat_fee_one_default ON public.ecom_flat_fee USING btree (country_code, COALESCE(tenant_id, 0)) WHERE is_default;


--
-- Name: uq_email_tpl_tenant_name; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_email_tpl_tenant_name ON public.email_template_config USING btree (tenant_id, name);


--
-- Name: uq_feed_amount_contract; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_feed_amount_contract ON public.feed_amount_contract USING btree (COALESCE(tenant_id, 0), COALESCE(country_code, '*'::character varying), column_name);


--
-- Name: uq_ic_norm_run_applied; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_ic_norm_run_applied ON public.interchange_normalization_run USING btree (tenant_id, month_key) WHERE ((status)::text = 'APPLIED'::text);


--
-- Name: uq_mcc_sector_map_key; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_mcc_sector_map_key ON public.mcc_sector_map USING btree (country_code, COALESCE(tenant_id, 0), mcc);


--
-- Name: uq_ref_bin_range_mc_key; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_ref_bin_range_mc_key ON public.ref_bin_range USING btree (range_low, COALESCE(product_code, ''::character varying)) WHERE ((scheme)::text = 'MASTERCARD'::text);


--
-- Name: uq_ref_ecom_fx_rate; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_ref_ecom_fx_rate ON public.ref_ecom_fx_rate USING btree (tenant_id, COALESCE(mid, '*'::character varying), txn_currency);


--
-- Name: uq_revenue_leakage_flag; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_revenue_leakage_flag ON public.revenue_leakage_flags USING btree (tenant_id, merchant_id, check_type, business_date);


--
-- Name: uq_sales_country_lead_tenant_email; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_sales_country_lead_tenant_email ON public.sales_country_lead USING btree (tenant_id, country_lead_email);


--
-- Name: uq_scheme_fee_rate_key; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_scheme_fee_rate_key ON public.scheme_fee_rate USING btree (country_code, COALESCE(tenant_id, 0), dest, channel, COALESCE(scheme_group, ''::character varying), COALESCE(card_type, ''::character varying), COALESCE(effective_from, '1900-01-01'::date));


--
-- Name: uq_sys_menu_key; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_sys_menu_key ON public.sys_menu USING btree (menu_key);


--
-- Name: uq_sys_user_group_scope; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_sys_user_group_scope ON public.sys_user_group USING btree (COALESCE(owner_tenant_id, 0), group_name);


--
-- Name: uq_terminal_channel_map; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_terminal_channel_map ON public.terminal_channel_map USING btree (country_code, COALESCE(tenant_id, 0), raw_type);


--
-- Name: uq_ticket_size_bucket; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_ticket_size_bucket ON public.ticket_size_bucket USING btree (country_code, COALESCE(tenant_id, 0), seq);


--
-- Name: ux_fact_rental_tenant_hash; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX ux_fact_rental_tenant_hash ON public.fact_rental USING btree (tenant_id, row_hash);


--
-- Name: fact_transaction_default_merchant_id_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_fact_txn_merchant ATTACH PARTITION public.fact_transaction_default_merchant_id_idx;


--
-- Name: fact_transaction_default_pkey; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.fact_transaction_pkey ATTACH PARTITION public.fact_transaction_default_pkey;


--
-- Name: fact_transaction_default_tenant_id_fee_resolution_status_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_fact_fee_resolution ATTACH PARTITION public.fact_transaction_default_tenant_id_fee_resolution_status_idx;


--
-- Name: fact_transaction_default_tenant_id_merchant_id_payment_date_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_fact_txn_tenant_merchant_date ATTACH PARTITION public.fact_transaction_default_tenant_id_merchant_id_payment_date_idx;


--
-- Name: fact_transaction_default_tenant_id_payment_date_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_fact_txn_tenant_date ATTACH PARTITION public.fact_transaction_default_tenant_id_payment_date_idx;


--
-- Name: sum_daily_bank_default_pkey; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.sum_daily_bank_pkey ATTACH PARTITION public.sum_daily_bank_default_pkey;


--
-- Name: sum_daily_bank_default_tenant_id_business_date_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sum_bank_tenant_date ATTACH PARTITION public.sum_daily_bank_default_tenant_id_business_date_idx;


--
-- Name: sum_daily_bank_default_tenant_id_business_date_key; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.sum_daily_bank_tenant_id_business_date_key ATTACH PARTITION public.sum_daily_bank_default_tenant_id_business_date_key;


--
-- Name: sum_daily_bank_y2024_pkey; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.sum_daily_bank_pkey ATTACH PARTITION public.sum_daily_bank_y2024_pkey;


--
-- Name: sum_daily_bank_y2024_tenant_id_business_date_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sum_bank_tenant_date ATTACH PARTITION public.sum_daily_bank_y2024_tenant_id_business_date_idx;


--
-- Name: sum_daily_bank_y2024_tenant_id_business_date_key; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.sum_daily_bank_tenant_id_business_date_key ATTACH PARTITION public.sum_daily_bank_y2024_tenant_id_business_date_key;


--
-- Name: sum_daily_bank_y2025_pkey; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.sum_daily_bank_pkey ATTACH PARTITION public.sum_daily_bank_y2025_pkey;


--
-- Name: sum_daily_bank_y2025_tenant_id_business_date_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sum_bank_tenant_date ATTACH PARTITION public.sum_daily_bank_y2025_tenant_id_business_date_idx;


--
-- Name: sum_daily_bank_y2025_tenant_id_business_date_key; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.sum_daily_bank_tenant_id_business_date_key ATTACH PARTITION public.sum_daily_bank_y2025_tenant_id_business_date_key;


--
-- Name: sum_daily_bank_y2026_pkey; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.sum_daily_bank_pkey ATTACH PARTITION public.sum_daily_bank_y2026_pkey;


--
-- Name: sum_daily_bank_y2026_tenant_id_business_date_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sum_bank_tenant_date ATTACH PARTITION public.sum_daily_bank_y2026_tenant_id_business_date_idx;


--
-- Name: sum_daily_bank_y2026_tenant_id_business_date_key; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.sum_daily_bank_tenant_id_business_date_key ATTACH PARTITION public.sum_daily_bank_y2026_tenant_id_business_date_key;


--
-- Name: sum_daily_bank_y2027_pkey; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.sum_daily_bank_pkey ATTACH PARTITION public.sum_daily_bank_y2027_pkey;


--
-- Name: sum_daily_bank_y2027_tenant_id_business_date_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sum_bank_tenant_date ATTACH PARTITION public.sum_daily_bank_y2027_tenant_id_business_date_idx;


--
-- Name: sum_daily_bank_y2027_tenant_id_business_date_key; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.sum_daily_bank_tenant_id_business_date_key ATTACH PARTITION public.sum_daily_bank_y2027_tenant_id_business_date_key;


--
-- Name: sum_daily_channel_default_pkey; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.sum_daily_channel_pkey ATTACH PARTITION public.sum_daily_channel_default_pkey;


--
-- Name: sum_daily_channel_default_tenant_id_business_date_channel_key; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.sum_daily_channel_tenant_id_business_date_channel_key ATTACH PARTITION public.sum_daily_channel_default_tenant_id_business_date_channel_key;


--
-- Name: sum_daily_channel_default_tenant_id_business_date_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sum_channel_tenant_date ATTACH PARTITION public.sum_daily_channel_default_tenant_id_business_date_idx;


--
-- Name: sum_daily_channel_y2024_pkey; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.sum_daily_channel_pkey ATTACH PARTITION public.sum_daily_channel_y2024_pkey;


--
-- Name: sum_daily_channel_y2024_tenant_id_business_date_channel_key; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.sum_daily_channel_tenant_id_business_date_channel_key ATTACH PARTITION public.sum_daily_channel_y2024_tenant_id_business_date_channel_key;


--
-- Name: sum_daily_channel_y2024_tenant_id_business_date_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sum_channel_tenant_date ATTACH PARTITION public.sum_daily_channel_y2024_tenant_id_business_date_idx;


--
-- Name: sum_daily_channel_y2025_pkey; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.sum_daily_channel_pkey ATTACH PARTITION public.sum_daily_channel_y2025_pkey;


--
-- Name: sum_daily_channel_y2025_tenant_id_business_date_channel_key; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.sum_daily_channel_tenant_id_business_date_channel_key ATTACH PARTITION public.sum_daily_channel_y2025_tenant_id_business_date_channel_key;


--
-- Name: sum_daily_channel_y2025_tenant_id_business_date_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sum_channel_tenant_date ATTACH PARTITION public.sum_daily_channel_y2025_tenant_id_business_date_idx;


--
-- Name: sum_daily_channel_y2026_pkey; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.sum_daily_channel_pkey ATTACH PARTITION public.sum_daily_channel_y2026_pkey;


--
-- Name: sum_daily_channel_y2026_tenant_id_business_date_channel_key; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.sum_daily_channel_tenant_id_business_date_channel_key ATTACH PARTITION public.sum_daily_channel_y2026_tenant_id_business_date_channel_key;


--
-- Name: sum_daily_channel_y2026_tenant_id_business_date_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sum_channel_tenant_date ATTACH PARTITION public.sum_daily_channel_y2026_tenant_id_business_date_idx;


--
-- Name: sum_daily_channel_y2027_pkey; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.sum_daily_channel_pkey ATTACH PARTITION public.sum_daily_channel_y2027_pkey;


--
-- Name: sum_daily_channel_y2027_tenant_id_business_date_channel_key; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.sum_daily_channel_tenant_id_business_date_channel_key ATTACH PARTITION public.sum_daily_channel_y2027_tenant_id_business_date_channel_key;


--
-- Name: sum_daily_channel_y2027_tenant_id_business_date_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sum_channel_tenant_date ATTACH PARTITION public.sum_daily_channel_y2027_tenant_id_business_date_idx;


--
-- Name: sum_daily_explorer_default_pkey; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.sum_daily_explorer_pkey ATTACH PARTITION public.sum_daily_explorer_default_pkey;


--
-- Name: sum_daily_explorer_default_tenant_id_business_date_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sum_daily_explorer_tenant_date ATTACH PARTITION public.sum_daily_explorer_default_tenant_id_business_date_idx;


--
-- Name: sum_daily_explorer_default_tenant_id_business_date_merchant_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sum_daily_explorer_tenant_date_merch ATTACH PARTITION public.sum_daily_explorer_default_tenant_id_business_date_merchant_idx;


--
-- Name: sum_daily_explorer_default_tenant_id_business_date_merchant_key; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.sum_daily_explorer_tenant_id_business_date_merchant_id_stor_key ATTACH PARTITION public.sum_daily_explorer_default_tenant_id_business_date_merchant_key;


--
-- Name: sum_daily_explorer_y2024_pkey; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.sum_daily_explorer_pkey ATTACH PARTITION public.sum_daily_explorer_y2024_pkey;


--
-- Name: sum_daily_explorer_y2024_tenant_id_business_date_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sum_daily_explorer_tenant_date ATTACH PARTITION public.sum_daily_explorer_y2024_tenant_id_business_date_idx;


--
-- Name: sum_daily_explorer_y2024_tenant_id_business_date_merchant_i_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sum_daily_explorer_tenant_date_merch ATTACH PARTITION public.sum_daily_explorer_y2024_tenant_id_business_date_merchant_i_idx;


--
-- Name: sum_daily_explorer_y2024_tenant_id_business_date_merchant_i_key; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.sum_daily_explorer_tenant_id_business_date_merchant_id_stor_key ATTACH PARTITION public.sum_daily_explorer_y2024_tenant_id_business_date_merchant_i_key;


--
-- Name: sum_daily_explorer_y2025_pkey; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.sum_daily_explorer_pkey ATTACH PARTITION public.sum_daily_explorer_y2025_pkey;


--
-- Name: sum_daily_explorer_y2025_tenant_id_business_date_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sum_daily_explorer_tenant_date ATTACH PARTITION public.sum_daily_explorer_y2025_tenant_id_business_date_idx;


--
-- Name: sum_daily_explorer_y2025_tenant_id_business_date_merchant_i_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sum_daily_explorer_tenant_date_merch ATTACH PARTITION public.sum_daily_explorer_y2025_tenant_id_business_date_merchant_i_idx;


--
-- Name: sum_daily_explorer_y2025_tenant_id_business_date_merchant_i_key; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.sum_daily_explorer_tenant_id_business_date_merchant_id_stor_key ATTACH PARTITION public.sum_daily_explorer_y2025_tenant_id_business_date_merchant_i_key;


--
-- Name: sum_daily_explorer_y2026_pkey; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.sum_daily_explorer_pkey ATTACH PARTITION public.sum_daily_explorer_y2026_pkey;


--
-- Name: sum_daily_explorer_y2026_tenant_id_business_date_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sum_daily_explorer_tenant_date ATTACH PARTITION public.sum_daily_explorer_y2026_tenant_id_business_date_idx;


--
-- Name: sum_daily_explorer_y2026_tenant_id_business_date_merchant_i_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sum_daily_explorer_tenant_date_merch ATTACH PARTITION public.sum_daily_explorer_y2026_tenant_id_business_date_merchant_i_idx;


--
-- Name: sum_daily_explorer_y2026_tenant_id_business_date_merchant_i_key; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.sum_daily_explorer_tenant_id_business_date_merchant_id_stor_key ATTACH PARTITION public.sum_daily_explorer_y2026_tenant_id_business_date_merchant_i_key;


--
-- Name: sum_daily_explorer_y2027_pkey; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.sum_daily_explorer_pkey ATTACH PARTITION public.sum_daily_explorer_y2027_pkey;


--
-- Name: sum_daily_explorer_y2027_tenant_id_business_date_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sum_daily_explorer_tenant_date ATTACH PARTITION public.sum_daily_explorer_y2027_tenant_id_business_date_idx;


--
-- Name: sum_daily_explorer_y2027_tenant_id_business_date_merchant_i_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sum_daily_explorer_tenant_date_merch ATTACH PARTITION public.sum_daily_explorer_y2027_tenant_id_business_date_merchant_i_idx;


--
-- Name: sum_daily_explorer_y2027_tenant_id_business_date_merchant_i_key; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.sum_daily_explorer_tenant_id_business_date_merchant_id_stor_key ATTACH PARTITION public.sum_daily_explorer_y2027_tenant_id_business_date_merchant_i_key;


--
-- Name: sum_daily_finance_default_pkey; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.sum_daily_finance_pkey ATTACH PARTITION public.sum_daily_finance_default_pkey;


--
-- Name: sum_daily_finance_default_tenant_id_business_date_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sum_fin_tenant_date ATTACH PARTITION public.sum_daily_finance_default_tenant_id_business_date_idx;


--
-- Name: sum_daily_finance_default_tenant_id_business_date_key; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.sum_daily_finance_tenant_id_business_date_key ATTACH PARTITION public.sum_daily_finance_default_tenant_id_business_date_key;


--
-- Name: sum_daily_finance_y2024_pkey; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.sum_daily_finance_pkey ATTACH PARTITION public.sum_daily_finance_y2024_pkey;


--
-- Name: sum_daily_finance_y2024_tenant_id_business_date_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sum_fin_tenant_date ATTACH PARTITION public.sum_daily_finance_y2024_tenant_id_business_date_idx;


--
-- Name: sum_daily_finance_y2024_tenant_id_business_date_key; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.sum_daily_finance_tenant_id_business_date_key ATTACH PARTITION public.sum_daily_finance_y2024_tenant_id_business_date_key;


--
-- Name: sum_daily_finance_y2025_pkey; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.sum_daily_finance_pkey ATTACH PARTITION public.sum_daily_finance_y2025_pkey;


--
-- Name: sum_daily_finance_y2025_tenant_id_business_date_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sum_fin_tenant_date ATTACH PARTITION public.sum_daily_finance_y2025_tenant_id_business_date_idx;


--
-- Name: sum_daily_finance_y2025_tenant_id_business_date_key; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.sum_daily_finance_tenant_id_business_date_key ATTACH PARTITION public.sum_daily_finance_y2025_tenant_id_business_date_key;


--
-- Name: sum_daily_finance_y2026_pkey; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.sum_daily_finance_pkey ATTACH PARTITION public.sum_daily_finance_y2026_pkey;


--
-- Name: sum_daily_finance_y2026_tenant_id_business_date_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sum_fin_tenant_date ATTACH PARTITION public.sum_daily_finance_y2026_tenant_id_business_date_idx;


--
-- Name: sum_daily_finance_y2026_tenant_id_business_date_key; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.sum_daily_finance_tenant_id_business_date_key ATTACH PARTITION public.sum_daily_finance_y2026_tenant_id_business_date_key;


--
-- Name: sum_daily_finance_y2027_pkey; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.sum_daily_finance_pkey ATTACH PARTITION public.sum_daily_finance_y2027_pkey;


--
-- Name: sum_daily_finance_y2027_tenant_id_business_date_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sum_fin_tenant_date ATTACH PARTITION public.sum_daily_finance_y2027_tenant_id_business_date_idx;


--
-- Name: sum_daily_finance_y2027_tenant_id_business_date_key; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.sum_daily_finance_tenant_id_business_date_key ATTACH PARTITION public.sum_daily_finance_y2027_tenant_id_business_date_key;


--
-- Name: sum_daily_full_default_pkey; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.sum_daily_full_pkey ATTACH PARTITION public.sum_daily_full_default_pkey;


--
-- Name: sum_daily_full_default_tenant_id_business_date_destination__idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sum_daily_full_fee_overlay ATTACH PARTITION public.sum_daily_full_default_tenant_id_business_date_destination__idx;


--
-- Name: sum_daily_full_default_tenant_id_business_date_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sum_daily_full_tenant_date ATTACH PARTITION public.sum_daily_full_default_tenant_id_business_date_idx;


--
-- Name: sum_daily_full_default_tenant_id_business_date_merchant_id__key; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.sum_daily_full_tenant_id_business_date_merchant_id_store_id_key ATTACH PARTITION public.sum_daily_full_default_tenant_id_business_date_merchant_id__key;


--
-- Name: sum_daily_full_default_tenant_id_business_date_merchant_id_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sum_daily_full_tenant_date_merch ATTACH PARTITION public.sum_daily_full_default_tenant_id_business_date_merchant_id_idx;


--
-- Name: sum_daily_full_y2024_pkey; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.sum_daily_full_pkey ATTACH PARTITION public.sum_daily_full_y2024_pkey;


--
-- Name: sum_daily_full_y2024_tenant_id_business_date_destination_ca_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sum_daily_full_fee_overlay ATTACH PARTITION public.sum_daily_full_y2024_tenant_id_business_date_destination_ca_idx;


--
-- Name: sum_daily_full_y2024_tenant_id_business_date_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sum_daily_full_tenant_date ATTACH PARTITION public.sum_daily_full_y2024_tenant_id_business_date_idx;


--
-- Name: sum_daily_full_y2024_tenant_id_business_date_merchant_id_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sum_daily_full_tenant_date_merch ATTACH PARTITION public.sum_daily_full_y2024_tenant_id_business_date_merchant_id_idx;


--
-- Name: sum_daily_full_y2024_tenant_id_business_date_merchant_id_st_key; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.sum_daily_full_tenant_id_business_date_merchant_id_store_id_key ATTACH PARTITION public.sum_daily_full_y2024_tenant_id_business_date_merchant_id_st_key;


--
-- Name: sum_daily_full_y2025_pkey; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.sum_daily_full_pkey ATTACH PARTITION public.sum_daily_full_y2025_pkey;


--
-- Name: sum_daily_full_y2025_tenant_id_business_date_destination_ca_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sum_daily_full_fee_overlay ATTACH PARTITION public.sum_daily_full_y2025_tenant_id_business_date_destination_ca_idx;


--
-- Name: sum_daily_full_y2025_tenant_id_business_date_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sum_daily_full_tenant_date ATTACH PARTITION public.sum_daily_full_y2025_tenant_id_business_date_idx;


--
-- Name: sum_daily_full_y2025_tenant_id_business_date_merchant_id_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sum_daily_full_tenant_date_merch ATTACH PARTITION public.sum_daily_full_y2025_tenant_id_business_date_merchant_id_idx;


--
-- Name: sum_daily_full_y2025_tenant_id_business_date_merchant_id_st_key; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.sum_daily_full_tenant_id_business_date_merchant_id_store_id_key ATTACH PARTITION public.sum_daily_full_y2025_tenant_id_business_date_merchant_id_st_key;


--
-- Name: sum_daily_full_y2026_pkey; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.sum_daily_full_pkey ATTACH PARTITION public.sum_daily_full_y2026_pkey;


--
-- Name: sum_daily_full_y2026_tenant_id_business_date_destination_ca_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sum_daily_full_fee_overlay ATTACH PARTITION public.sum_daily_full_y2026_tenant_id_business_date_destination_ca_idx;


--
-- Name: sum_daily_full_y2026_tenant_id_business_date_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sum_daily_full_tenant_date ATTACH PARTITION public.sum_daily_full_y2026_tenant_id_business_date_idx;


--
-- Name: sum_daily_full_y2026_tenant_id_business_date_merchant_id_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sum_daily_full_tenant_date_merch ATTACH PARTITION public.sum_daily_full_y2026_tenant_id_business_date_merchant_id_idx;


--
-- Name: sum_daily_full_y2026_tenant_id_business_date_merchant_id_st_key; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.sum_daily_full_tenant_id_business_date_merchant_id_store_id_key ATTACH PARTITION public.sum_daily_full_y2026_tenant_id_business_date_merchant_id_st_key;


--
-- Name: sum_daily_full_y2027_pkey; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.sum_daily_full_pkey ATTACH PARTITION public.sum_daily_full_y2027_pkey;


--
-- Name: sum_daily_full_y2027_tenant_id_business_date_destination_ca_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sum_daily_full_fee_overlay ATTACH PARTITION public.sum_daily_full_y2027_tenant_id_business_date_destination_ca_idx;


--
-- Name: sum_daily_full_y2027_tenant_id_business_date_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sum_daily_full_tenant_date ATTACH PARTITION public.sum_daily_full_y2027_tenant_id_business_date_idx;


--
-- Name: sum_daily_full_y2027_tenant_id_business_date_merchant_id_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sum_daily_full_tenant_date_merch ATTACH PARTITION public.sum_daily_full_y2027_tenant_id_business_date_merchant_id_idx;


--
-- Name: sum_daily_full_y2027_tenant_id_business_date_merchant_id_st_key; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.sum_daily_full_tenant_id_business_date_merchant_id_store_id_key ATTACH PARTITION public.sum_daily_full_y2027_tenant_id_business_date_merchant_id_st_key;


--
-- Name: sum_daily_insight_default_merchant_id_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sum_insight_merchant ATTACH PARTITION public.sum_daily_insight_default_merchant_id_idx;


--
-- Name: sum_daily_insight_default_pkey; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.sum_daily_insight_pkey ATTACH PARTITION public.sum_daily_insight_default_pkey;


--
-- Name: sum_daily_insight_default_tenant_id_business_date_card_sche_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sdi_card_rollup ATTACH PARTITION public.sum_daily_insight_default_tenant_id_business_date_card_sche_idx;


--
-- Name: sum_daily_insight_default_tenant_id_business_date_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sum_insight_tenant_date ATTACH PARTITION public.sum_daily_insight_default_tenant_id_business_date_idx;


--
-- Name: sum_daily_insight_default_tenant_id_business_date_merchant__idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sdi_merchant_rollup ATTACH PARTITION public.sum_daily_insight_default_tenant_id_business_date_merchant__idx;


--
-- Name: sum_daily_insight_default_tenant_id_business_date_merchant__key; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.sum_daily_insight_tenant_id_business_date_merchant_id_store_key ATTACH PARTITION public.sum_daily_insight_default_tenant_id_business_date_merchant__key;


--
-- Name: sum_daily_insight_default_tenant_id_business_date_merchant_idx1; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sdi_summary_rollup ATTACH PARTITION public.sum_daily_insight_default_tenant_id_business_date_merchant_idx1;


--
-- Name: sum_daily_insight_y2024_merchant_id_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sum_insight_merchant ATTACH PARTITION public.sum_daily_insight_y2024_merchant_id_idx;


--
-- Name: sum_daily_insight_y2024_pkey; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.sum_daily_insight_pkey ATTACH PARTITION public.sum_daily_insight_y2024_pkey;


--
-- Name: sum_daily_insight_y2024_tenant_id_business_date_card_scheme_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sdi_card_rollup ATTACH PARTITION public.sum_daily_insight_y2024_tenant_id_business_date_card_scheme_idx;


--
-- Name: sum_daily_insight_y2024_tenant_id_business_date_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sum_insight_tenant_date ATTACH PARTITION public.sum_daily_insight_y2024_tenant_id_business_date_idx;


--
-- Name: sum_daily_insight_y2024_tenant_id_business_date_merchant_i_idx1; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sdi_summary_rollup ATTACH PARTITION public.sum_daily_insight_y2024_tenant_id_business_date_merchant_i_idx1;


--
-- Name: sum_daily_insight_y2024_tenant_id_business_date_merchant_id_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sdi_merchant_rollup ATTACH PARTITION public.sum_daily_insight_y2024_tenant_id_business_date_merchant_id_idx;


--
-- Name: sum_daily_insight_y2024_tenant_id_business_date_merchant_id_key; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.sum_daily_insight_tenant_id_business_date_merchant_id_store_key ATTACH PARTITION public.sum_daily_insight_y2024_tenant_id_business_date_merchant_id_key;


--
-- Name: sum_daily_insight_y2025_merchant_id_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sum_insight_merchant ATTACH PARTITION public.sum_daily_insight_y2025_merchant_id_idx;


--
-- Name: sum_daily_insight_y2025_pkey; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.sum_daily_insight_pkey ATTACH PARTITION public.sum_daily_insight_y2025_pkey;


--
-- Name: sum_daily_insight_y2025_tenant_id_business_date_card_scheme_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sdi_card_rollup ATTACH PARTITION public.sum_daily_insight_y2025_tenant_id_business_date_card_scheme_idx;


--
-- Name: sum_daily_insight_y2025_tenant_id_business_date_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sum_insight_tenant_date ATTACH PARTITION public.sum_daily_insight_y2025_tenant_id_business_date_idx;


--
-- Name: sum_daily_insight_y2025_tenant_id_business_date_merchant_i_idx1; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sdi_summary_rollup ATTACH PARTITION public.sum_daily_insight_y2025_tenant_id_business_date_merchant_i_idx1;


--
-- Name: sum_daily_insight_y2025_tenant_id_business_date_merchant_id_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sdi_merchant_rollup ATTACH PARTITION public.sum_daily_insight_y2025_tenant_id_business_date_merchant_id_idx;


--
-- Name: sum_daily_insight_y2025_tenant_id_business_date_merchant_id_key; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.sum_daily_insight_tenant_id_business_date_merchant_id_store_key ATTACH PARTITION public.sum_daily_insight_y2025_tenant_id_business_date_merchant_id_key;


--
-- Name: sum_daily_insight_y2026_merchant_id_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sum_insight_merchant ATTACH PARTITION public.sum_daily_insight_y2026_merchant_id_idx;


--
-- Name: sum_daily_insight_y2026_pkey; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.sum_daily_insight_pkey ATTACH PARTITION public.sum_daily_insight_y2026_pkey;


--
-- Name: sum_daily_insight_y2026_tenant_id_business_date_card_scheme_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sdi_card_rollup ATTACH PARTITION public.sum_daily_insight_y2026_tenant_id_business_date_card_scheme_idx;


--
-- Name: sum_daily_insight_y2026_tenant_id_business_date_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sum_insight_tenant_date ATTACH PARTITION public.sum_daily_insight_y2026_tenant_id_business_date_idx;


--
-- Name: sum_daily_insight_y2026_tenant_id_business_date_merchant_i_idx1; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sdi_summary_rollup ATTACH PARTITION public.sum_daily_insight_y2026_tenant_id_business_date_merchant_i_idx1;


--
-- Name: sum_daily_insight_y2026_tenant_id_business_date_merchant_id_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sdi_merchant_rollup ATTACH PARTITION public.sum_daily_insight_y2026_tenant_id_business_date_merchant_id_idx;


--
-- Name: sum_daily_insight_y2026_tenant_id_business_date_merchant_id_key; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.sum_daily_insight_tenant_id_business_date_merchant_id_store_key ATTACH PARTITION public.sum_daily_insight_y2026_tenant_id_business_date_merchant_id_key;


--
-- Name: sum_daily_insight_y2027_merchant_id_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sum_insight_merchant ATTACH PARTITION public.sum_daily_insight_y2027_merchant_id_idx;


--
-- Name: sum_daily_insight_y2027_pkey; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.sum_daily_insight_pkey ATTACH PARTITION public.sum_daily_insight_y2027_pkey;


--
-- Name: sum_daily_insight_y2027_tenant_id_business_date_card_scheme_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sdi_card_rollup ATTACH PARTITION public.sum_daily_insight_y2027_tenant_id_business_date_card_scheme_idx;


--
-- Name: sum_daily_insight_y2027_tenant_id_business_date_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sum_insight_tenant_date ATTACH PARTITION public.sum_daily_insight_y2027_tenant_id_business_date_idx;


--
-- Name: sum_daily_insight_y2027_tenant_id_business_date_merchant_i_idx1; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sdi_summary_rollup ATTACH PARTITION public.sum_daily_insight_y2027_tenant_id_business_date_merchant_i_idx1;


--
-- Name: sum_daily_insight_y2027_tenant_id_business_date_merchant_id_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sdi_merchant_rollup ATTACH PARTITION public.sum_daily_insight_y2027_tenant_id_business_date_merchant_id_idx;


--
-- Name: sum_daily_insight_y2027_tenant_id_business_date_merchant_id_key; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.sum_daily_insight_tenant_id_business_date_merchant_id_store_key ATTACH PARTITION public.sum_daily_insight_y2027_tenant_id_business_date_merchant_id_key;


--
-- Name: sum_daily_local_debit_bin_def_tenant_id_business_date_merch_key; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.sum_daily_local_debit_bin_tenant_id_business_date_merchant__key ATTACH PARTITION public.sum_daily_local_debit_bin_def_tenant_id_business_date_merch_key;


--
-- Name: sum_daily_local_debit_bin_defa_tenant_id_business_date_bin6_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sum_daily_ldb_tenant_date_bin ATTACH PARTITION public.sum_daily_local_debit_bin_defa_tenant_id_business_date_bin6_idx;


--
-- Name: sum_daily_local_debit_bin_default_pkey; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.sum_daily_local_debit_bin_pkey ATTACH PARTITION public.sum_daily_local_debit_bin_default_pkey;


--
-- Name: sum_daily_local_debit_bin_default_tenant_id_business_date_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sum_daily_ldb_tenant_date ATTACH PARTITION public.sum_daily_local_debit_bin_default_tenant_id_business_date_idx;


--
-- Name: sum_daily_local_debit_bin_y2024_pkey; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.sum_daily_local_debit_bin_pkey ATTACH PARTITION public.sum_daily_local_debit_bin_y2024_pkey;


--
-- Name: sum_daily_local_debit_bin_y2024_tenant_id_business_date_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sum_daily_ldb_tenant_date ATTACH PARTITION public.sum_daily_local_debit_bin_y2024_tenant_id_business_date_idx;


--
-- Name: sum_daily_local_debit_bin_y2025_pkey; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.sum_daily_local_debit_bin_pkey ATTACH PARTITION public.sum_daily_local_debit_bin_y2025_pkey;


--
-- Name: sum_daily_local_debit_bin_y2025_tenant_id_business_date_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sum_daily_ldb_tenant_date ATTACH PARTITION public.sum_daily_local_debit_bin_y2025_tenant_id_business_date_idx;


--
-- Name: sum_daily_local_debit_bin_y2026_pkey; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.sum_daily_local_debit_bin_pkey ATTACH PARTITION public.sum_daily_local_debit_bin_y2026_pkey;


--
-- Name: sum_daily_local_debit_bin_y2026_tenant_id_business_date_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sum_daily_ldb_tenant_date ATTACH PARTITION public.sum_daily_local_debit_bin_y2026_tenant_id_business_date_idx;


--
-- Name: sum_daily_local_debit_bin_y2027_pkey; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.sum_daily_local_debit_bin_pkey ATTACH PARTITION public.sum_daily_local_debit_bin_y2027_pkey;


--
-- Name: sum_daily_local_debit_bin_y2027_tenant_id_business_date_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sum_daily_ldb_tenant_date ATTACH PARTITION public.sum_daily_local_debit_bin_y2027_tenant_id_business_date_idx;


--
-- Name: sum_daily_local_debit_bin_y202_tenant_id_business_date_bin6_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sum_daily_ldb_tenant_date_bin ATTACH PARTITION public.sum_daily_local_debit_bin_y202_tenant_id_business_date_bin6_idx;


--
-- Name: sum_daily_local_debit_bin_y20_tenant_id_business_date_bin6_idx1; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sum_daily_ldb_tenant_date_bin ATTACH PARTITION public.sum_daily_local_debit_bin_y20_tenant_id_business_date_bin6_idx1;


--
-- Name: sum_daily_local_debit_bin_y20_tenant_id_business_date_bin6_idx2; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sum_daily_ldb_tenant_date_bin ATTACH PARTITION public.sum_daily_local_debit_bin_y20_tenant_id_business_date_bin6_idx2;


--
-- Name: sum_daily_local_debit_bin_y20_tenant_id_business_date_bin6_idx3; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sum_daily_ldb_tenant_date_bin ATTACH PARTITION public.sum_daily_local_debit_bin_y20_tenant_id_business_date_bin6_idx3;


--
-- Name: sum_daily_local_debit_bin_y20_tenant_id_business_date_merc_key1; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.sum_daily_local_debit_bin_tenant_id_business_date_merchant__key ATTACH PARTITION public.sum_daily_local_debit_bin_y20_tenant_id_business_date_merc_key1;


--
-- Name: sum_daily_local_debit_bin_y20_tenant_id_business_date_merc_key2; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.sum_daily_local_debit_bin_tenant_id_business_date_merchant__key ATTACH PARTITION public.sum_daily_local_debit_bin_y20_tenant_id_business_date_merc_key2;


--
-- Name: sum_daily_local_debit_bin_y20_tenant_id_business_date_merc_key3; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.sum_daily_local_debit_bin_tenant_id_business_date_merchant__key ATTACH PARTITION public.sum_daily_local_debit_bin_y20_tenant_id_business_date_merc_key3;


--
-- Name: sum_daily_local_debit_bin_y20_tenant_id_business_date_merch_key; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.sum_daily_local_debit_bin_tenant_id_business_date_merchant__key ATTACH PARTITION public.sum_daily_local_debit_bin_y20_tenant_id_business_date_merch_key;


--
-- Name: sum_daily_merch_attr_default_attribute_type_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sdma_attr_type ATTACH PARTITION public.sum_daily_merch_attr_default_attribute_type_idx;


--
-- Name: sum_daily_merch_attr_default_merchant_id_business_date_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sdma_merchant_date ATTACH PARTITION public.sum_daily_merch_attr_default_merchant_id_business_date_idx;


--
-- Name: sum_daily_merch_attr_default_pkey; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.sum_daily_merchant_attribute_pkey ATTACH PARTITION public.sum_daily_merch_attr_default_pkey;


--
-- Name: sum_daily_merch_attr_default_tenant_id_business_date_mercha_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sdma_tenant_date_merchant ATTACH PARTITION public.sum_daily_merch_attr_default_tenant_id_business_date_mercha_idx;


--
-- Name: sum_daily_merch_attr_default_tenant_id_merchant_id_business_key; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.sum_daily_merchant_attribute_tenant_id_merchant_id_business_key ATTACH PARTITION public.sum_daily_merch_attr_default_tenant_id_merchant_id_business_key;


--
-- Name: sum_daily_merch_attr_y2024_attribute_type_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sdma_attr_type ATTACH PARTITION public.sum_daily_merch_attr_y2024_attribute_type_idx;


--
-- Name: sum_daily_merch_attr_y2024_merchant_id_business_date_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sdma_merchant_date ATTACH PARTITION public.sum_daily_merch_attr_y2024_merchant_id_business_date_idx;


--
-- Name: sum_daily_merch_attr_y2024_pkey; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.sum_daily_merchant_attribute_pkey ATTACH PARTITION public.sum_daily_merch_attr_y2024_pkey;


--
-- Name: sum_daily_merch_attr_y2024_tenant_id_business_date_merchant_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sdma_tenant_date_merchant ATTACH PARTITION public.sum_daily_merch_attr_y2024_tenant_id_business_date_merchant_idx;


--
-- Name: sum_daily_merch_attr_y2024_tenant_id_merchant_id_business_d_key; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.sum_daily_merchant_attribute_tenant_id_merchant_id_business_key ATTACH PARTITION public.sum_daily_merch_attr_y2024_tenant_id_merchant_id_business_d_key;


--
-- Name: sum_daily_merch_attr_y2025_attribute_type_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sdma_attr_type ATTACH PARTITION public.sum_daily_merch_attr_y2025_attribute_type_idx;


--
-- Name: sum_daily_merch_attr_y2025_merchant_id_business_date_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sdma_merchant_date ATTACH PARTITION public.sum_daily_merch_attr_y2025_merchant_id_business_date_idx;


--
-- Name: sum_daily_merch_attr_y2025_pkey; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.sum_daily_merchant_attribute_pkey ATTACH PARTITION public.sum_daily_merch_attr_y2025_pkey;


--
-- Name: sum_daily_merch_attr_y2025_tenant_id_business_date_merchant_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sdma_tenant_date_merchant ATTACH PARTITION public.sum_daily_merch_attr_y2025_tenant_id_business_date_merchant_idx;


--
-- Name: sum_daily_merch_attr_y2025_tenant_id_merchant_id_business_d_key; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.sum_daily_merchant_attribute_tenant_id_merchant_id_business_key ATTACH PARTITION public.sum_daily_merch_attr_y2025_tenant_id_merchant_id_business_d_key;


--
-- Name: sum_daily_merch_attr_y2026_attribute_type_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sdma_attr_type ATTACH PARTITION public.sum_daily_merch_attr_y2026_attribute_type_idx;


--
-- Name: sum_daily_merch_attr_y2026_merchant_id_business_date_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sdma_merchant_date ATTACH PARTITION public.sum_daily_merch_attr_y2026_merchant_id_business_date_idx;


--
-- Name: sum_daily_merch_attr_y2026_pkey; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.sum_daily_merchant_attribute_pkey ATTACH PARTITION public.sum_daily_merch_attr_y2026_pkey;


--
-- Name: sum_daily_merch_attr_y2026_tenant_id_business_date_merchant_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sdma_tenant_date_merchant ATTACH PARTITION public.sum_daily_merch_attr_y2026_tenant_id_business_date_merchant_idx;


--
-- Name: sum_daily_merch_attr_y2026_tenant_id_merchant_id_business_d_key; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.sum_daily_merchant_attribute_tenant_id_merchant_id_business_key ATTACH PARTITION public.sum_daily_merch_attr_y2026_tenant_id_merchant_id_business_d_key;


--
-- Name: sum_daily_merchant_default_merchant_id_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sum_merch_id ATTACH PARTITION public.sum_daily_merchant_default_merchant_id_idx;


--
-- Name: sum_daily_merchant_default_pkey; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.sum_daily_merchant_pkey ATTACH PARTITION public.sum_daily_merchant_default_pkey;


--
-- Name: sum_daily_merchant_default_tenant_id_business_date_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sum_merch_tenant_date ATTACH PARTITION public.sum_daily_merchant_default_tenant_id_business_date_idx;


--
-- Name: sum_daily_merchant_default_tenant_id_business_date_idx1; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sum_daily_merchant_tenant_date ATTACH PARTITION public.sum_daily_merchant_default_tenant_id_business_date_idx1;


--
-- Name: sum_daily_merchant_default_tenant_id_business_date_merchant_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sdm_attrition_rollup ATTACH PARTITION public.sum_daily_merchant_default_tenant_id_business_date_merchant_idx;


--
-- Name: sum_daily_merchant_default_tenant_id_business_date_merchant_key; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.sum_daily_merchant_tenant_id_business_date_merchant_id_key ATTACH PARTITION public.sum_daily_merchant_default_tenant_id_business_date_merchant_key;


--
-- Name: sum_daily_merchant_destinatio_tenant_id_business_date_dest_idx1; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sum_merch_dest_tenant_date_dest ATTACH PARTITION public.sum_daily_merchant_destinatio_tenant_id_business_date_dest_idx1;


--
-- Name: sum_daily_merchant_destinatio_tenant_id_business_date_dest_idx2; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sum_merch_dest_tenant_date_dest ATTACH PARTITION public.sum_daily_merchant_destinatio_tenant_id_business_date_dest_idx2;


--
-- Name: sum_daily_merchant_destinatio_tenant_id_business_date_dest_idx3; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sum_merch_dest_tenant_date_dest ATTACH PARTITION public.sum_daily_merchant_destinatio_tenant_id_business_date_dest_idx3;


--
-- Name: sum_daily_merchant_destinatio_tenant_id_business_date_dest_idx4; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sum_merch_dest_tenant_date_dest ATTACH PARTITION public.sum_daily_merchant_destinatio_tenant_id_business_date_dest_idx4;


--
-- Name: sum_daily_merchant_destinatio_tenant_id_business_date_desti_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sum_merch_dest_tenant_date_dest ATTACH PARTITION public.sum_daily_merchant_destinatio_tenant_id_business_date_desti_idx;


--
-- Name: sum_daily_merchant_destinatio_tenant_id_business_date_merc_idx1; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sum_merch_dest_tenant_date_merch ATTACH PARTITION public.sum_daily_merchant_destinatio_tenant_id_business_date_merc_idx1;


--
-- Name: sum_daily_merchant_destinatio_tenant_id_business_date_merc_idx2; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sum_merch_dest_tenant_date_merch ATTACH PARTITION public.sum_daily_merchant_destinatio_tenant_id_business_date_merc_idx2;


--
-- Name: sum_daily_merchant_destinatio_tenant_id_business_date_merc_idx3; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sum_merch_dest_tenant_date_merch ATTACH PARTITION public.sum_daily_merchant_destinatio_tenant_id_business_date_merc_idx3;


--
-- Name: sum_daily_merchant_destinatio_tenant_id_business_date_merc_idx4; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sum_merch_dest_tenant_date_merch ATTACH PARTITION public.sum_daily_merchant_destinatio_tenant_id_business_date_merc_idx4;


--
-- Name: sum_daily_merchant_destinatio_tenant_id_business_date_merc_key1; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.sum_daily_merchant_destinatio_tenant_id_business_date_merch_key ATTACH PARTITION public.sum_daily_merchant_destinatio_tenant_id_business_date_merc_key1;


--
-- Name: sum_daily_merchant_destinatio_tenant_id_business_date_merc_key2; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.sum_daily_merchant_destinatio_tenant_id_business_date_merch_key ATTACH PARTITION public.sum_daily_merchant_destinatio_tenant_id_business_date_merc_key2;


--
-- Name: sum_daily_merchant_destinatio_tenant_id_business_date_merc_key3; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.sum_daily_merchant_destinatio_tenant_id_business_date_merch_key ATTACH PARTITION public.sum_daily_merchant_destinatio_tenant_id_business_date_merc_key3;


--
-- Name: sum_daily_merchant_destinatio_tenant_id_business_date_merc_key4; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.sum_daily_merchant_destinatio_tenant_id_business_date_merch_key ATTACH PARTITION public.sum_daily_merchant_destinatio_tenant_id_business_date_merc_key4;


--
-- Name: sum_daily_merchant_destinatio_tenant_id_business_date_merc_key5; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.sum_daily_merchant_destinatio_tenant_id_business_date_merch_key ATTACH PARTITION public.sum_daily_merchant_destinatio_tenant_id_business_date_merc_key5;


--
-- Name: sum_daily_merchant_destinatio_tenant_id_business_date_merch_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sum_merch_dest_tenant_date_merch ATTACH PARTITION public.sum_daily_merchant_destinatio_tenant_id_business_date_merch_idx;


--
-- Name: sum_daily_merchant_destination_default_pkey; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.sum_daily_merchant_destination_pkey ATTACH PARTITION public.sum_daily_merchant_destination_default_pkey;


--
-- Name: sum_daily_merchant_destination_y2024_pkey; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.sum_daily_merchant_destination_pkey ATTACH PARTITION public.sum_daily_merchant_destination_y2024_pkey;


--
-- Name: sum_daily_merchant_destination_y2025_pkey; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.sum_daily_merchant_destination_pkey ATTACH PARTITION public.sum_daily_merchant_destination_y2025_pkey;


--
-- Name: sum_daily_merchant_destination_y2026_pkey; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.sum_daily_merchant_destination_pkey ATTACH PARTITION public.sum_daily_merchant_destination_y2026_pkey;


--
-- Name: sum_daily_merchant_destination_y2027_pkey; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.sum_daily_merchant_destination_pkey ATTACH PARTITION public.sum_daily_merchant_destination_y2027_pkey;


--
-- Name: sum_daily_merchant_y2024_merchant_id_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sum_merch_id ATTACH PARTITION public.sum_daily_merchant_y2024_merchant_id_idx;


--
-- Name: sum_daily_merchant_y2024_pkey; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.sum_daily_merchant_pkey ATTACH PARTITION public.sum_daily_merchant_y2024_pkey;


--
-- Name: sum_daily_merchant_y2024_tenant_id_business_date_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sum_merch_tenant_date ATTACH PARTITION public.sum_daily_merchant_y2024_tenant_id_business_date_idx;


--
-- Name: sum_daily_merchant_y2024_tenant_id_business_date_idx1; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sum_daily_merchant_tenant_date ATTACH PARTITION public.sum_daily_merchant_y2024_tenant_id_business_date_idx1;


--
-- Name: sum_daily_merchant_y2024_tenant_id_business_date_merchant_i_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sdm_attrition_rollup ATTACH PARTITION public.sum_daily_merchant_y2024_tenant_id_business_date_merchant_i_idx;


--
-- Name: sum_daily_merchant_y2024_tenant_id_business_date_merchant_i_key; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.sum_daily_merchant_tenant_id_business_date_merchant_id_key ATTACH PARTITION public.sum_daily_merchant_y2024_tenant_id_business_date_merchant_i_key;


--
-- Name: sum_daily_merchant_y2025_merchant_id_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sum_merch_id ATTACH PARTITION public.sum_daily_merchant_y2025_merchant_id_idx;


--
-- Name: sum_daily_merchant_y2025_pkey; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.sum_daily_merchant_pkey ATTACH PARTITION public.sum_daily_merchant_y2025_pkey;


--
-- Name: sum_daily_merchant_y2025_tenant_id_business_date_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sum_merch_tenant_date ATTACH PARTITION public.sum_daily_merchant_y2025_tenant_id_business_date_idx;


--
-- Name: sum_daily_merchant_y2025_tenant_id_business_date_idx1; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sum_daily_merchant_tenant_date ATTACH PARTITION public.sum_daily_merchant_y2025_tenant_id_business_date_idx1;


--
-- Name: sum_daily_merchant_y2025_tenant_id_business_date_merchant_i_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sdm_attrition_rollup ATTACH PARTITION public.sum_daily_merchant_y2025_tenant_id_business_date_merchant_i_idx;


--
-- Name: sum_daily_merchant_y2025_tenant_id_business_date_merchant_i_key; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.sum_daily_merchant_tenant_id_business_date_merchant_id_key ATTACH PARTITION public.sum_daily_merchant_y2025_tenant_id_business_date_merchant_i_key;


--
-- Name: sum_daily_merchant_y2026_merchant_id_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sum_merch_id ATTACH PARTITION public.sum_daily_merchant_y2026_merchant_id_idx;


--
-- Name: sum_daily_merchant_y2026_pkey; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.sum_daily_merchant_pkey ATTACH PARTITION public.sum_daily_merchant_y2026_pkey;


--
-- Name: sum_daily_merchant_y2026_tenant_id_business_date_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sum_merch_tenant_date ATTACH PARTITION public.sum_daily_merchant_y2026_tenant_id_business_date_idx;


--
-- Name: sum_daily_merchant_y2026_tenant_id_business_date_idx1; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sum_daily_merchant_tenant_date ATTACH PARTITION public.sum_daily_merchant_y2026_tenant_id_business_date_idx1;


--
-- Name: sum_daily_merchant_y2026_tenant_id_business_date_merchant_i_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sdm_attrition_rollup ATTACH PARTITION public.sum_daily_merchant_y2026_tenant_id_business_date_merchant_i_idx;


--
-- Name: sum_daily_merchant_y2026_tenant_id_business_date_merchant_i_key; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.sum_daily_merchant_tenant_id_business_date_merchant_id_key ATTACH PARTITION public.sum_daily_merchant_y2026_tenant_id_business_date_merchant_i_key;


--
-- Name: sum_daily_scheme_default_pkey; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.sum_daily_scheme_pkey ATTACH PARTITION public.sum_daily_scheme_default_pkey;


--
-- Name: sum_daily_scheme_default_tenant_id_business_date_card_schem_key; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.sum_daily_scheme_tenant_id_business_date_card_scheme_key ATTACH PARTITION public.sum_daily_scheme_default_tenant_id_business_date_card_schem_key;


--
-- Name: sum_daily_scheme_default_tenant_id_business_date_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sum_scheme_tenant_date ATTACH PARTITION public.sum_daily_scheme_default_tenant_id_business_date_idx;


--
-- Name: sum_daily_scheme_y2024_pkey; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.sum_daily_scheme_pkey ATTACH PARTITION public.sum_daily_scheme_y2024_pkey;


--
-- Name: sum_daily_scheme_y2024_tenant_id_business_date_card_scheme_key; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.sum_daily_scheme_tenant_id_business_date_card_scheme_key ATTACH PARTITION public.sum_daily_scheme_y2024_tenant_id_business_date_card_scheme_key;


--
-- Name: sum_daily_scheme_y2024_tenant_id_business_date_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sum_scheme_tenant_date ATTACH PARTITION public.sum_daily_scheme_y2024_tenant_id_business_date_idx;


--
-- Name: sum_daily_scheme_y2025_pkey; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.sum_daily_scheme_pkey ATTACH PARTITION public.sum_daily_scheme_y2025_pkey;


--
-- Name: sum_daily_scheme_y2025_tenant_id_business_date_card_scheme_key; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.sum_daily_scheme_tenant_id_business_date_card_scheme_key ATTACH PARTITION public.sum_daily_scheme_y2025_tenant_id_business_date_card_scheme_key;


--
-- Name: sum_daily_scheme_y2025_tenant_id_business_date_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sum_scheme_tenant_date ATTACH PARTITION public.sum_daily_scheme_y2025_tenant_id_business_date_idx;


--
-- Name: sum_daily_scheme_y2026_pkey; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.sum_daily_scheme_pkey ATTACH PARTITION public.sum_daily_scheme_y2026_pkey;


--
-- Name: sum_daily_scheme_y2026_tenant_id_business_date_card_scheme_key; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.sum_daily_scheme_tenant_id_business_date_card_scheme_key ATTACH PARTITION public.sum_daily_scheme_y2026_tenant_id_business_date_card_scheme_key;


--
-- Name: sum_daily_scheme_y2026_tenant_id_business_date_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sum_scheme_tenant_date ATTACH PARTITION public.sum_daily_scheme_y2026_tenant_id_business_date_idx;


--
-- Name: sum_daily_scheme_y2027_pkey; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.sum_daily_scheme_pkey ATTACH PARTITION public.sum_daily_scheme_y2027_pkey;


--
-- Name: sum_daily_scheme_y2027_tenant_id_business_date_card_scheme_key; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.sum_daily_scheme_tenant_id_business_date_card_scheme_key ATTACH PARTITION public.sum_daily_scheme_y2027_tenant_id_business_date_card_scheme_key;


--
-- Name: sum_daily_scheme_y2027_tenant_id_business_date_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sum_scheme_tenant_date ATTACH PARTITION public.sum_daily_scheme_y2027_tenant_id_business_date_idx;


--
-- Name: sum_daily_terminal_default_merchant_id_terminal_id_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sum_term_ids ATTACH PARTITION public.sum_daily_terminal_default_merchant_id_terminal_id_idx;


--
-- Name: sum_daily_terminal_default_pkey; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.sum_daily_terminal_pkey ATTACH PARTITION public.sum_daily_terminal_default_pkey;


--
-- Name: sum_daily_terminal_default_tenant_id_business_date_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sum_term_tenant_date ATTACH PARTITION public.sum_daily_terminal_default_tenant_id_business_date_idx;


--
-- Name: sum_daily_terminal_default_tenant_id_business_date_merchan_idx1; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.sum_daily_terminal_grain_key ATTACH PARTITION public.sum_daily_terminal_default_tenant_id_business_date_merchan_idx1;


--
-- Name: sum_daily_terminal_default_tenant_id_business_date_merchant_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sdt_fee_rollup ATTACH PARTITION public.sum_daily_terminal_default_tenant_id_business_date_merchant_idx;


--
-- Name: sum_daily_terminal_default_tenant_id_terminal_id_business_d_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sum_daily_terminal_tenant_terminal_date ATTACH PARTITION public.sum_daily_terminal_default_tenant_id_terminal_id_business_d_idx;


--
-- Name: sum_daily_terminal_y2024_merchant_id_terminal_id_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sum_term_ids ATTACH PARTITION public.sum_daily_terminal_y2024_merchant_id_terminal_id_idx;


--
-- Name: sum_daily_terminal_y2024_pkey; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.sum_daily_terminal_pkey ATTACH PARTITION public.sum_daily_terminal_y2024_pkey;


--
-- Name: sum_daily_terminal_y2024_tenant_id_business_date_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sum_term_tenant_date ATTACH PARTITION public.sum_daily_terminal_y2024_tenant_id_business_date_idx;


--
-- Name: sum_daily_terminal_y2024_tenant_id_business_date_merchant__idx1; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.sum_daily_terminal_grain_key ATTACH PARTITION public.sum_daily_terminal_y2024_tenant_id_business_date_merchant__idx1;


--
-- Name: sum_daily_terminal_y2024_tenant_id_business_date_merchant_i_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sdt_fee_rollup ATTACH PARTITION public.sum_daily_terminal_y2024_tenant_id_business_date_merchant_i_idx;


--
-- Name: sum_daily_terminal_y2024_tenant_id_terminal_id_business_dat_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sum_daily_terminal_tenant_terminal_date ATTACH PARTITION public.sum_daily_terminal_y2024_tenant_id_terminal_id_business_dat_idx;


--
-- Name: sum_daily_terminal_y2025_merchant_id_terminal_id_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sum_term_ids ATTACH PARTITION public.sum_daily_terminal_y2025_merchant_id_terminal_id_idx;


--
-- Name: sum_daily_terminal_y2025_pkey; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.sum_daily_terminal_pkey ATTACH PARTITION public.sum_daily_terminal_y2025_pkey;


--
-- Name: sum_daily_terminal_y2025_tenant_id_business_date_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sum_term_tenant_date ATTACH PARTITION public.sum_daily_terminal_y2025_tenant_id_business_date_idx;


--
-- Name: sum_daily_terminal_y2025_tenant_id_business_date_merchant__idx1; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.sum_daily_terminal_grain_key ATTACH PARTITION public.sum_daily_terminal_y2025_tenant_id_business_date_merchant__idx1;


--
-- Name: sum_daily_terminal_y2025_tenant_id_business_date_merchant_i_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sdt_fee_rollup ATTACH PARTITION public.sum_daily_terminal_y2025_tenant_id_business_date_merchant_i_idx;


--
-- Name: sum_daily_terminal_y2025_tenant_id_terminal_id_business_dat_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sum_daily_terminal_tenant_terminal_date ATTACH PARTITION public.sum_daily_terminal_y2025_tenant_id_terminal_id_business_dat_idx;


--
-- Name: sum_daily_terminal_y2026_merchant_id_terminal_id_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sum_term_ids ATTACH PARTITION public.sum_daily_terminal_y2026_merchant_id_terminal_id_idx;


--
-- Name: sum_daily_terminal_y2026_pkey; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.sum_daily_terminal_pkey ATTACH PARTITION public.sum_daily_terminal_y2026_pkey;


--
-- Name: sum_daily_terminal_y2026_tenant_id_business_date_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sum_term_tenant_date ATTACH PARTITION public.sum_daily_terminal_y2026_tenant_id_business_date_idx;


--
-- Name: sum_daily_terminal_y2026_tenant_id_business_date_merchant__idx1; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.sum_daily_terminal_grain_key ATTACH PARTITION public.sum_daily_terminal_y2026_tenant_id_business_date_merchant__idx1;


--
-- Name: sum_daily_terminal_y2026_tenant_id_business_date_merchant_i_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sdt_fee_rollup ATTACH PARTITION public.sum_daily_terminal_y2026_tenant_id_business_date_merchant_i_idx;


--
-- Name: sum_daily_terminal_y2026_tenant_id_terminal_id_business_dat_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sum_daily_terminal_tenant_terminal_date ATTACH PARTITION public.sum_daily_terminal_y2026_tenant_id_terminal_id_business_dat_idx;


--
-- Name: sum_daily_terminal_y2027_merchant_id_terminal_id_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sum_term_ids ATTACH PARTITION public.sum_daily_terminal_y2027_merchant_id_terminal_id_idx;


--
-- Name: sum_daily_terminal_y2027_pkey; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.sum_daily_terminal_pkey ATTACH PARTITION public.sum_daily_terminal_y2027_pkey;


--
-- Name: sum_daily_terminal_y2027_tenant_id_business_date_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sum_term_tenant_date ATTACH PARTITION public.sum_daily_terminal_y2027_tenant_id_business_date_idx;


--
-- Name: sum_daily_terminal_y2027_tenant_id_business_date_merchant__idx1; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.sum_daily_terminal_grain_key ATTACH PARTITION public.sum_daily_terminal_y2027_tenant_id_business_date_merchant__idx1;


--
-- Name: sum_daily_terminal_y2027_tenant_id_business_date_merchant_i_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sdt_fee_rollup ATTACH PARTITION public.sum_daily_terminal_y2027_tenant_id_business_date_merchant_i_idx;


--
-- Name: sum_daily_terminal_y2027_tenant_id_terminal_id_business_dat_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.idx_sum_daily_terminal_tenant_terminal_date ATTACH PARTITION public.sum_daily_terminal_y2027_tenant_id_terminal_id_business_dat_idx;


--
-- Name: access_request access_request_requested_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.access_request
    ADD CONSTRAINT access_request_requested_tenant_id_fkey FOREIGN KEY (requested_tenant_id) REFERENCES public.tenant(tenant_id);


--
-- Name: access_request access_request_reviewed_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.access_request
    ADD CONSTRAINT access_request_reviewed_by_fkey FOREIGN KEY (reviewed_by) REFERENCES public.users(user_id);


--
-- Name: ai_chat_history ai_chat_history_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ai_chat_history
    ADD CONSTRAINT ai_chat_history_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(user_id);


--
-- Name: alert_history alert_history_rule_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.alert_history
    ADD CONSTRAINT alert_history_rule_id_fkey FOREIGN KEY (rule_id) REFERENCES public.alert_rule(rule_id) ON DELETE SET NULL;


--
-- Name: alert_history alert_history_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.alert_history
    ADD CONSTRAINT alert_history_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenant(tenant_id) ON DELETE CASCADE;


--
-- Name: alert_rule alert_rule_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.alert_rule
    ADD CONSTRAINT alert_rule_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenant(tenant_id) ON DELETE CASCADE;


--
-- Name: api_key api_key_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.api_key
    ADD CONSTRAINT api_key_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenant(tenant_id) ON DELETE CASCADE;


--
-- Name: api_request_log api_request_log_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.api_request_log
    ADD CONSTRAINT api_request_log_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenant(tenant_id) ON DELETE CASCADE;


--
-- Name: audit_log audit_log_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.audit_log
    ADD CONSTRAINT audit_log_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenant(tenant_id);


--
-- Name: audit_log audit_log_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.audit_log
    ADD CONSTRAINT audit_log_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(user_id);


--
-- Name: bank_budget_target bank_budget_target_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.bank_budget_target
    ADD CONSTRAINT bank_budget_target_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenant(tenant_id);


--
-- Name: dashboard_config dashboard_config_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dashboard_config
    ADD CONSTRAINT dashboard_config_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenant(tenant_id) ON DELETE CASCADE;


--
-- Name: destination_token_map destination_token_map_country_code_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.destination_token_map
    ADD CONSTRAINT destination_token_map_country_code_fkey FOREIGN KEY (country_code) REFERENCES public.ref_country(country_code);


--
-- Name: dim_aggregator dim_aggregator_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dim_aggregator
    ADD CONSTRAINT dim_aggregator_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenant(tenant_id);


--
-- Name: dim_bank_account dim_bank_account_store_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dim_bank_account
    ADD CONSTRAINT dim_bank_account_store_id_fkey FOREIGN KEY (store_id) REFERENCES public.dim_store(store_id);


--
-- Name: dim_bank_account dim_bank_account_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dim_bank_account
    ADD CONSTRAINT dim_bank_account_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenant(tenant_id);


--
-- Name: dim_merchant dim_merchant_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dim_merchant
    ADD CONSTRAINT dim_merchant_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenant(tenant_id);


--
-- Name: dim_store dim_store_merchant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dim_store
    ADD CONSTRAINT dim_store_merchant_id_fkey FOREIGN KEY (merchant_id) REFERENCES public.dim_merchant(merchant_id);


--
-- Name: dim_store dim_store_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dim_store
    ADD CONSTRAINT dim_store_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenant(tenant_id);


--
-- Name: dim_terminal dim_terminal_store_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dim_terminal
    ADD CONSTRAINT dim_terminal_store_id_fkey FOREIGN KEY (store_id) REFERENCES public.dim_store(store_id);


--
-- Name: dim_terminal dim_terminal_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dim_terminal
    ADD CONSTRAINT dim_terminal_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenant(tenant_id);


--
-- Name: email_campaign_log email_campaign_log_campaign_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.email_campaign_log
    ADD CONSTRAINT email_campaign_log_campaign_id_fkey FOREIGN KEY (campaign_id) REFERENCES public.email_campaign(id);


--
-- Name: email_campaign email_campaign_template_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.email_campaign
    ADD CONSTRAINT email_campaign_template_id_fkey FOREIGN KEY (template_id) REFERENCES public.email_template_config(id);


--
-- Name: feed_amount_contract feed_amount_contract_country_code_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.feed_amount_contract
    ADD CONSTRAINT feed_amount_contract_country_code_fkey FOREIGN KEY (country_code) REFERENCES public.ref_country(country_code);


--
-- Name: ecom_flat_fee fk_ecom_flat_fee_country; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ecom_flat_fee
    ADD CONSTRAINT fk_ecom_flat_fee_country FOREIGN KEY (country_code) REFERENCES public.ref_country(country_code);


--
-- Name: interchange_rate_local fk_interchange_rate_local_country; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.interchange_rate_local
    ADD CONSTRAINT fk_interchange_rate_local_country FOREIGN KEY (country_code) REFERENCES public.ref_country(country_code);


--
-- Name: mcc_sector_map fk_mcc_sector_map_country; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mcc_sector_map
    ADD CONSTRAINT fk_mcc_sector_map_country FOREIGN KEY (country_code) REFERENCES public.ref_country(country_code);


--
-- Name: scheme_fee_rate fk_scheme_fee_rate_country; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.scheme_fee_rate
    ADD CONSTRAINT fk_scheme_fee_rate_country FOREIGN KEY (country_code) REFERENCES public.ref_country(country_code);


--
-- Name: tenant fk_tenant_home_country; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tenant
    ADD CONSTRAINT fk_tenant_home_country FOREIGN KEY (home_country_code) REFERENCES public.ref_country(country_code);


--
-- Name: ingest_run_stage ingest_run_stage_run_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ingest_run_stage
    ADD CONSTRAINT ingest_run_stage_run_id_fkey FOREIGN KEY (run_id) REFERENCES public.ingest_run(id) ON DELETE CASCADE;


--
-- Name: integration_report integration_report_connection_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.integration_report
    ADD CONSTRAINT integration_report_connection_id_fkey FOREIGN KEY (connection_id) REFERENCES public.integration_connection(id);


--
-- Name: integration_run_log integration_run_log_report_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.integration_run_log
    ADD CONSTRAINT integration_run_log_report_id_fkey FOREIGN KEY (report_id) REFERENCES public.integration_report(id);


--
-- Name: integration_run_log integration_run_log_schedule_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.integration_run_log
    ADD CONSTRAINT integration_run_log_schedule_id_fkey FOREIGN KEY (schedule_id) REFERENCES public.integration_schedule(id);


--
-- Name: integration_schedule integration_schedule_report_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.integration_schedule
    ADD CONSTRAINT integration_schedule_report_id_fkey FOREIGN KEY (report_id) REFERENCES public.integration_report(id);


--
-- Name: interchange_normalization_detail interchange_normalization_detail_run_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.interchange_normalization_detail
    ADD CONSTRAINT interchange_normalization_detail_run_id_fkey FOREIGN KEY (run_id) REFERENCES public.interchange_normalization_run(run_id) ON DELETE CASCADE;


--
-- Name: interchange_transaction interchange_transaction_file_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.interchange_transaction
    ADD CONSTRAINT interchange_transaction_file_id_fkey FOREIGN KEY (file_id) REFERENCES public.interchange_file(id) ON DELETE CASCADE;


--
-- Name: interchange_violation interchange_violation_file_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.interchange_violation
    ADD CONSTRAINT interchange_violation_file_id_fkey FOREIGN KEY (file_id) REFERENCES public.interchange_file(id) ON DELETE CASCADE;


--
-- Name: interchange_violation interchange_violation_transaction_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.interchange_violation
    ADD CONSTRAINT interchange_violation_transaction_id_fkey FOREIGN KEY (transaction_id) REFERENCES public.interchange_transaction(id) ON DELETE CASCADE;


--
-- Name: batch_job_execution_context job_exec_ctx_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.batch_job_execution_context
    ADD CONSTRAINT job_exec_ctx_fk FOREIGN KEY (job_execution_id) REFERENCES public.batch_job_execution(job_execution_id);


--
-- Name: batch_job_execution_params job_exec_params_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.batch_job_execution_params
    ADD CONSTRAINT job_exec_params_fk FOREIGN KEY (job_execution_id) REFERENCES public.batch_job_execution(job_execution_id);


--
-- Name: batch_step_execution job_exec_step_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.batch_step_execution
    ADD CONSTRAINT job_exec_step_fk FOREIGN KEY (job_execution_id) REFERENCES public.batch_job_execution(job_execution_id);


--
-- Name: batch_job_execution job_inst_exec_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.batch_job_execution
    ADD CONSTRAINT job_inst_exec_fk FOREIGN KEY (job_instance_id) REFERENCES public.batch_job_instance(job_instance_id);


--
-- Name: kpi_snapshot_daily kpi_snapshot_daily_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.kpi_snapshot_daily
    ADD CONSTRAINT kpi_snapshot_daily_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenant(tenant_id);


--
-- Name: kpi_snapshot_monthly kpi_snapshot_monthly_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.kpi_snapshot_monthly
    ADD CONSTRAINT kpi_snapshot_monthly_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenant(tenant_id);


--
-- Name: login_mfa_token login_mfa_token_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.login_mfa_token
    ADD CONSTRAINT login_mfa_token_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(user_id) ON DELETE CASCADE;


--
-- Name: merchant_activity merchant_activity_merchant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.merchant_activity
    ADD CONSTRAINT merchant_activity_merchant_id_fkey FOREIGN KEY (merchant_id) REFERENCES public.dim_merchant(merchant_id);


--
-- Name: merchant_activity_summary merchant_activity_summary_merchant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.merchant_activity_summary
    ADD CONSTRAINT merchant_activity_summary_merchant_id_fkey FOREIGN KEY (merchant_id) REFERENCES public.dim_merchant(merchant_id);


--
-- Name: merchant_activity_summary merchant_activity_summary_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.merchant_activity_summary
    ADD CONSTRAINT merchant_activity_summary_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenant(tenant_id);


--
-- Name: merchant_activity merchant_activity_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.merchant_activity
    ADD CONSTRAINT merchant_activity_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenant(tenant_id);


--
-- Name: merchant_contact merchant_contact_merchant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.merchant_contact
    ADD CONSTRAINT merchant_contact_merchant_id_fkey FOREIGN KEY (merchant_id) REFERENCES public.dim_merchant(merchant_id);


--
-- Name: merchant_contact merchant_contact_store_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.merchant_contact
    ADD CONSTRAINT merchant_contact_store_id_fkey FOREIGN KEY (store_id) REFERENCES public.dim_store(store_id);


--
-- Name: merchant_contact merchant_contact_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.merchant_contact
    ADD CONSTRAINT merchant_contact_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenant(tenant_id);


--
-- Name: merchant_contract merchant_contract_merchant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.merchant_contract
    ADD CONSTRAINT merchant_contract_merchant_id_fkey FOREIGN KEY (merchant_id) REFERENCES public.dim_merchant(merchant_id);


--
-- Name: merchant_contract merchant_contract_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.merchant_contract
    ADD CONSTRAINT merchant_contract_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenant(tenant_id);


--
-- Name: merchant_daily_metrics merchant_daily_metrics_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.merchant_daily_metrics
    ADD CONSTRAINT merchant_daily_metrics_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenant(tenant_id);


--
-- Name: merchant_document merchant_document_merchant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.merchant_document
    ADD CONSTRAINT merchant_document_merchant_id_fkey FOREIGN KEY (merchant_id) REFERENCES public.dim_merchant(merchant_id);


--
-- Name: merchant_document merchant_document_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.merchant_document
    ADD CONSTRAINT merchant_document_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenant(tenant_id);


--
-- Name: merchant_lifecycle_status merchant_lifecycle_status_merchant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.merchant_lifecycle_status
    ADD CONSTRAINT merchant_lifecycle_status_merchant_id_fkey FOREIGN KEY (merchant_id) REFERENCES public.dim_merchant(merchant_id);


--
-- Name: merchant_lifecycle_status merchant_lifecycle_status_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.merchant_lifecycle_status
    ADD CONSTRAINT merchant_lifecycle_status_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenant(tenant_id);


--
-- Name: merchant_note merchant_note_merchant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.merchant_note
    ADD CONSTRAINT merchant_note_merchant_id_fkey FOREIGN KEY (merchant_id) REFERENCES public.dim_merchant(merchant_id);


--
-- Name: merchant_note merchant_note_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.merchant_note
    ADD CONSTRAINT merchant_note_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenant(tenant_id);


--
-- Name: merchant_opportunity_score merchant_opportunity_score_merchant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.merchant_opportunity_score
    ADD CONSTRAINT merchant_opportunity_score_merchant_id_fkey FOREIGN KEY (merchant_id) REFERENCES public.dim_merchant(merchant_id);


--
-- Name: merchant_opportunity_score merchant_opportunity_score_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.merchant_opportunity_score
    ADD CONSTRAINT merchant_opportunity_score_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenant(tenant_id);


--
-- Name: merchant_risk_profile merchant_risk_profile_merchant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.merchant_risk_profile
    ADD CONSTRAINT merchant_risk_profile_merchant_id_fkey FOREIGN KEY (merchant_id) REFERENCES public.dim_merchant(merchant_id);


--
-- Name: merchant_risk_profile merchant_risk_profile_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.merchant_risk_profile
    ADD CONSTRAINT merchant_risk_profile_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenant(tenant_id);


--
-- Name: merchant_settlement_config merchant_settlement_config_merchant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.merchant_settlement_config
    ADD CONSTRAINT merchant_settlement_config_merchant_id_fkey FOREIGN KEY (merchant_id) REFERENCES public.dim_merchant(merchant_id);


--
-- Name: merchant_settlement_config merchant_settlement_config_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.merchant_settlement_config
    ADD CONSTRAINT merchant_settlement_config_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenant(tenant_id);


--
-- Name: mpe_record mpe_record_file_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mpe_record
    ADD CONSTRAINT mpe_record_file_id_fkey FOREIGN KEY (file_id) REFERENCES public.mpe_file(id) ON DELETE CASCADE;


--
-- Name: mpe_table_directory mpe_table_directory_file_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mpe_table_directory
    ADD CONSTRAINT mpe_table_directory_file_id_fkey FOREIGN KEY (file_id) REFERENCES public.mpe_file(id) ON DELETE CASCADE;


--
-- Name: password_history password_history_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.password_history
    ADD CONSTRAINT password_history_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(user_id) ON DELETE CASCADE;


--
-- Name: password_reset_token password_reset_token_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.password_reset_token
    ADD CONSTRAINT password_reset_token_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(user_id) ON DELETE CASCADE;


--
-- Name: profitability_adjustment_rate profitability_adjustment_rate_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.profitability_adjustment_rate
    ADD CONSTRAINT profitability_adjustment_rate_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenant(tenant_id);


--
-- Name: report_query_config report_query_config_source_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.report_query_config
    ADD CONSTRAINT report_query_config_source_id_fkey FOREIGN KEY (source_id) REFERENCES public.data_source_config(id);


--
-- Name: report_run_log report_run_log_query_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.report_run_log
    ADD CONSTRAINT report_run_log_query_id_fkey FOREIGN KEY (query_id) REFERENCES public.report_query_config(id);


--
-- Name: report_schedule report_schedule_template_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.report_schedule
    ADD CONSTRAINT report_schedule_template_id_fkey FOREIGN KEY (template_id) REFERENCES public.report_template(id);


--
-- Name: revenue_leakage_flags revenue_leakage_flags_merchant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.revenue_leakage_flags
    ADD CONSTRAINT revenue_leakage_flags_merchant_id_fkey FOREIGN KEY (merchant_id) REFERENCES public.dim_merchant(merchant_id);


--
-- Name: revenue_leakage_flags revenue_leakage_flags_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.revenue_leakage_flags
    ADD CONSTRAINT revenue_leakage_flags_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenant(tenant_id);


--
-- Name: sales_agent_target sales_agent_target_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sales_agent_target
    ADD CONSTRAINT sales_agent_target_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenant(tenant_id);


--
-- Name: sales_user_assignment sales_user_assignment_team_lead_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sales_user_assignment
    ADD CONSTRAINT sales_user_assignment_team_lead_id_fkey FOREIGN KEY (team_lead_id) REFERENCES public.sales_team_mapping(id);


--
-- Name: batch_step_execution_context step_exec_ctx_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.batch_step_execution_context
    ADD CONSTRAINT step_exec_ctx_fk FOREIGN KEY (step_execution_id) REFERENCES public.batch_step_execution(step_execution_id);


--
-- Name: sum_daily_mcc sum_daily_mcc_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_daily_mcc
    ADD CONSTRAINT sum_daily_mcc_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenant(tenant_id);


--
-- Name: sum_monthly_bank sum_monthly_bank_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_monthly_bank
    ADD CONSTRAINT sum_monthly_bank_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenant(tenant_id);


--
-- Name: sum_monthly_merchant_metrics sum_monthly_merchant_metrics_merchant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_monthly_merchant_metrics
    ADD CONSTRAINT sum_monthly_merchant_metrics_merchant_id_fkey FOREIGN KEY (merchant_id) REFERENCES public.dim_merchant(merchant_id);


--
-- Name: sum_monthly_merchant_metrics sum_monthly_merchant_metrics_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sum_monthly_merchant_metrics
    ADD CONSTRAINT sum_monthly_merchant_metrics_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenant(tenant_id);


--
-- Name: sys_group_menu sys_group_menu_group_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sys_group_menu
    ADD CONSTRAINT sys_group_menu_group_id_fkey FOREIGN KEY (group_id) REFERENCES public.sys_user_group(group_id);


--
-- Name: sys_group_menu sys_group_menu_menu_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sys_group_menu
    ADD CONSTRAINT sys_group_menu_menu_id_fkey FOREIGN KEY (menu_id) REFERENCES public.sys_menu(menu_id);


--
-- Name: sys_menu sys_menu_parent_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sys_menu
    ADD CONSTRAINT sys_menu_parent_id_fkey FOREIGN KEY (parent_id) REFERENCES public.sys_menu(menu_id);


--
-- Name: sys_user_group sys_user_group_owner_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sys_user_group
    ADD CONSTRAINT sys_user_group_owner_tenant_id_fkey FOREIGN KEY (owner_tenant_id) REFERENCES public.tenant(tenant_id) ON DELETE CASCADE;


--
-- Name: tenant_group tenant_group_cloned_from_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tenant_group
    ADD CONSTRAINT tenant_group_cloned_from_fkey FOREIGN KEY (cloned_from) REFERENCES public.sys_user_group(group_id);


--
-- Name: tenant_group tenant_group_group_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tenant_group
    ADD CONSTRAINT tenant_group_group_id_fkey FOREIGN KEY (group_id) REFERENCES public.sys_user_group(group_id) ON DELETE CASCADE;


--
-- Name: tenant_group_perm tenant_group_perm_group_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tenant_group_perm
    ADD CONSTRAINT tenant_group_perm_group_id_fkey FOREIGN KEY (group_id) REFERENCES public.sys_user_group(group_id) ON DELETE CASCADE;


--
-- Name: tenant_group_perm tenant_group_perm_perm_code_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tenant_group_perm
    ADD CONSTRAINT tenant_group_perm_perm_code_fkey FOREIGN KEY (perm_code) REFERENCES public.sys_permission(perm_code) ON DELETE CASCADE;


--
-- Name: tenant_group_perm tenant_group_perm_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tenant_group_perm
    ADD CONSTRAINT tenant_group_perm_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenant(tenant_id) ON DELETE CASCADE;


--
-- Name: tenant_group tenant_group_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tenant_group
    ADD CONSTRAINT tenant_group_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenant(tenant_id) ON DELETE CASCADE;


--
-- Name: tenant_module tenant_module_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tenant_module
    ADD CONSTRAINT tenant_module_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenant(tenant_id) ON DELETE CASCADE;


--
-- Name: tenant tenant_region_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tenant
    ADD CONSTRAINT tenant_region_id_fkey FOREIGN KEY (region_id) REFERENCES public.region(region_id);


--
-- Name: tenant_setting tenant_setting_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tenant_setting
    ADD CONSTRAINT tenant_setting_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenant(tenant_id) ON DELETE CASCADE;


--
-- Name: terminal_channel_map terminal_channel_map_country_code_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.terminal_channel_map
    ADD CONSTRAINT terminal_channel_map_country_code_fkey FOREIGN KEY (country_code) REFERENCES public.ref_country(country_code);


--
-- Name: ticket_size_bucket ticket_size_bucket_country_code_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ticket_size_bucket
    ADD CONSTRAINT ticket_size_bucket_country_code_fkey FOREIGN KEY (country_code) REFERENCES public.ref_country(country_code);


--
-- Name: user_region_access user_region_access_region_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_region_access
    ADD CONSTRAINT user_region_access_region_id_fkey FOREIGN KEY (region_id) REFERENCES public.region(region_id) ON DELETE CASCADE;


--
-- Name: user_region_access user_region_access_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_region_access
    ADD CONSTRAINT user_region_access_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(user_id) ON DELETE CASCADE;


--
-- Name: user_role user_role_role_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_role
    ADD CONSTRAINT user_role_role_id_fkey FOREIGN KEY (role_id) REFERENCES public.role(role_id) ON DELETE CASCADE;


--
-- Name: user_role user_role_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_role
    ADD CONSTRAINT user_role_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(user_id) ON DELETE CASCADE;


--
-- Name: user_tenant_access user_tenant_access_group_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_tenant_access
    ADD CONSTRAINT user_tenant_access_group_id_fkey FOREIGN KEY (group_id) REFERENCES public.sys_user_group(group_id);


--
-- Name: user_tenant_access user_tenant_access_tenant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_tenant_access
    ADD CONSTRAINT user_tenant_access_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES public.tenant(tenant_id) ON DELETE CASCADE;


--
-- Name: user_tenant_access user_tenant_access_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_tenant_access
    ADD CONSTRAINT user_tenant_access_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(user_id) ON DELETE CASCADE;


--
-- Name: webhook_delivery webhook_delivery_endpoint_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.webhook_delivery
    ADD CONSTRAINT webhook_delivery_endpoint_id_fkey FOREIGN KEY (endpoint_id) REFERENCES public.webhook_endpoint(endpoint_id) ON DELETE CASCADE;


--
-- Name: alert_history; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.alert_history ENABLE ROW LEVEL SECURITY;

--
-- Name: alert_rule; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.alert_rule ENABLE ROW LEVEL SECURITY;

--
-- Name: api_key; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.api_key ENABLE ROW LEVEL SECURITY;

--
-- Name: api_request_log; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.api_request_log ENABLE ROW LEVEL SECURITY;

--
-- Name: bank_budget_target; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.bank_budget_target ENABLE ROW LEVEL SECURITY;

--
-- Name: dim_bank_account; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.dim_bank_account ENABLE ROW LEVEL SECURITY;

--
-- Name: dim_merchant; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.dim_merchant ENABLE ROW LEVEL SECURITY;

--
-- Name: dim_store; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.dim_store ENABLE ROW LEVEL SECURITY;

--
-- Name: dim_terminal; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.dim_terminal ENABLE ROW LEVEL SECURITY;

--
-- Name: fact_dcc_revenue; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.fact_dcc_revenue ENABLE ROW LEVEL SECURITY;

--
-- Name: fact_rental; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.fact_rental ENABLE ROW LEVEL SECURITY;

--
-- Name: fact_transaction; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.fact_transaction ENABLE ROW LEVEL SECURITY;

--
-- Name: merchant_activity_summary; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.merchant_activity_summary ENABLE ROW LEVEL SECURITY;

--
-- Name: merchant_churn_score; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.merchant_churn_score ENABLE ROW LEVEL SECURITY;

--
-- Name: merchant_contact; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.merchant_contact ENABLE ROW LEVEL SECURITY;

--
-- Name: merchant_contract; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.merchant_contract ENABLE ROW LEVEL SECURITY;

--
-- Name: merchant_daily_metrics; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.merchant_daily_metrics ENABLE ROW LEVEL SECURITY;

--
-- Name: merchant_document; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.merchant_document ENABLE ROW LEVEL SECURITY;

--
-- Name: merchant_lifecycle_status; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.merchant_lifecycle_status ENABLE ROW LEVEL SECURITY;

--
-- Name: merchant_note; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.merchant_note ENABLE ROW LEVEL SECURITY;

--
-- Name: merchant_opportunity_score; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.merchant_opportunity_score ENABLE ROW LEVEL SECURITY;

--
-- Name: merchant_risk_profile; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.merchant_risk_profile ENABLE ROW LEVEL SECURITY;

--
-- Name: merchant_segment; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.merchant_segment ENABLE ROW LEVEL SECURITY;

--
-- Name: merchant_settlement_config; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.merchant_settlement_config ENABLE ROW LEVEL SECURITY;

--
-- Name: profitability_adjustment_rate; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.profitability_adjustment_rate ENABLE ROW LEVEL SECURITY;

--
-- Name: ref_tenant_bin_bank; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.ref_tenant_bin_bank ENABLE ROW LEVEL SECURITY;

--
-- Name: revenue_leakage_flags; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.revenue_leakage_flags ENABLE ROW LEVEL SECURITY;

--
-- Name: sales_agent_target; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.sales_agent_target ENABLE ROW LEVEL SECURITY;

--
-- Name: stg_dcc_revenue_raw; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.stg_dcc_revenue_raw ENABLE ROW LEVEL SECURITY;

--
-- Name: stg_merchant_master_raw; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.stg_merchant_master_raw ENABLE ROW LEVEL SECURITY;

--
-- Name: stg_rental_raw; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.stg_rental_raw ENABLE ROW LEVEL SECURITY;

--
-- Name: stg_trnx_raw; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.stg_trnx_raw ENABLE ROW LEVEL SECURITY;

--
-- Name: sum_daily_bank; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.sum_daily_bank ENABLE ROW LEVEL SECURITY;

--
-- Name: sum_daily_channel; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.sum_daily_channel ENABLE ROW LEVEL SECURITY;

--
-- Name: sum_daily_explorer; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.sum_daily_explorer ENABLE ROW LEVEL SECURITY;

--
-- Name: sum_daily_finance; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.sum_daily_finance ENABLE ROW LEVEL SECURITY;

--
-- Name: sum_daily_finance_rollup; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.sum_daily_finance_rollup ENABLE ROW LEVEL SECURITY;

--
-- Name: sum_daily_full; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.sum_daily_full ENABLE ROW LEVEL SECURITY;

--
-- Name: sum_daily_insight; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.sum_daily_insight ENABLE ROW LEVEL SECURITY;

--
-- Name: sum_daily_local_debit_bin; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.sum_daily_local_debit_bin ENABLE ROW LEVEL SECURITY;

--
-- Name: sum_daily_merchant; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.sum_daily_merchant ENABLE ROW LEVEL SECURITY;

--
-- Name: sum_daily_merchant_attribute; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.sum_daily_merchant_attribute ENABLE ROW LEVEL SECURITY;

--
-- Name: sum_daily_merchant_destination; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.sum_daily_merchant_destination ENABLE ROW LEVEL SECURITY;

--
-- Name: sum_daily_scheme; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.sum_daily_scheme ENABLE ROW LEVEL SECURITY;

--
-- Name: sum_daily_terminal; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.sum_daily_terminal ENABLE ROW LEVEL SECURITY;

--
-- Name: sum_monthly_bank; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.sum_monthly_bank ENABLE ROW LEVEL SECURITY;

--
-- Name: sum_monthly_card; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.sum_monthly_card ENABLE ROW LEVEL SECURITY;

--
-- Name: sum_monthly_insight; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.sum_monthly_insight ENABLE ROW LEVEL SECURITY;

--
-- Name: sum_monthly_merchant_metrics; Type: ROW SECURITY; Schema: public; Owner: -
--

ALTER TABLE public.sum_monthly_merchant_metrics ENABLE ROW LEVEL SECURITY;

--
-- Name: alert_history tenant_isolation_policy; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation_policy ON public.alert_history USING ((tenant_id = public.get_current_tenant()));


--
-- Name: alert_rule tenant_isolation_policy; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation_policy ON public.alert_rule USING ((tenant_id = public.get_current_tenant()));


--
-- Name: api_key tenant_isolation_policy; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation_policy ON public.api_key USING ((tenant_id = public.get_current_tenant()));


--
-- Name: api_request_log tenant_isolation_policy; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation_policy ON public.api_request_log USING ((tenant_id = public.get_current_tenant()));


--
-- Name: audit_log tenant_isolation_policy; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation_policy ON public.audit_log USING ((tenant_id = public.get_current_tenant()));


--
-- Name: bank_budget_target tenant_isolation_policy; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation_policy ON public.bank_budget_target USING ((tenant_id = public.get_current_tenant()));


--
-- Name: batch_run_log tenant_isolation_policy; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation_policy ON public.batch_run_log USING ((tenant_id = public.get_current_tenant()));


--
-- Name: dashboard_config tenant_isolation_policy; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation_policy ON public.dashboard_config USING ((tenant_id = public.get_current_tenant()));


--
-- Name: dim_bank_account tenant_isolation_policy; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation_policy ON public.dim_bank_account USING ((tenant_id = public.get_current_tenant()));


--
-- Name: dim_merchant tenant_isolation_policy; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation_policy ON public.dim_merchant USING ((tenant_id = public.get_current_tenant()));


--
-- Name: dim_store tenant_isolation_policy; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation_policy ON public.dim_store USING ((tenant_id = public.get_current_tenant()));


--
-- Name: dim_terminal tenant_isolation_policy; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation_policy ON public.dim_terminal USING ((tenant_id = public.get_current_tenant()));


--
-- Name: fact_dcc_revenue tenant_isolation_policy; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation_policy ON public.fact_dcc_revenue USING ((tenant_id = public.get_current_tenant()));


--
-- Name: fact_rental tenant_isolation_policy; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation_policy ON public.fact_rental USING ((tenant_id = public.get_current_tenant()));


--
-- Name: fact_transaction tenant_isolation_policy; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation_policy ON public.fact_transaction USING ((tenant_id = public.get_current_tenant()));


--
-- Name: kpi_snapshot_daily tenant_isolation_policy; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation_policy ON public.kpi_snapshot_daily USING ((tenant_id = public.get_current_tenant()));


--
-- Name: kpi_snapshot_monthly tenant_isolation_policy; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation_policy ON public.kpi_snapshot_monthly USING ((tenant_id = public.get_current_tenant()));


--
-- Name: merchant_activity tenant_isolation_policy; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation_policy ON public.merchant_activity USING ((tenant_id = public.get_current_tenant()));


--
-- Name: merchant_activity_summary tenant_isolation_policy; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation_policy ON public.merchant_activity_summary USING ((tenant_id = public.get_current_tenant()));


--
-- Name: merchant_churn_score tenant_isolation_policy; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation_policy ON public.merchant_churn_score USING ((tenant_id = public.get_current_tenant()));


--
-- Name: merchant_contact tenant_isolation_policy; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation_policy ON public.merchant_contact USING ((tenant_id = public.get_current_tenant()));


--
-- Name: merchant_contract tenant_isolation_policy; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation_policy ON public.merchant_contract USING ((tenant_id = public.get_current_tenant()));


--
-- Name: merchant_daily_metrics tenant_isolation_policy; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation_policy ON public.merchant_daily_metrics USING ((tenant_id = public.get_current_tenant()));


--
-- Name: merchant_document tenant_isolation_policy; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation_policy ON public.merchant_document USING ((tenant_id = public.get_current_tenant()));


--
-- Name: merchant_lifecycle_status tenant_isolation_policy; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation_policy ON public.merchant_lifecycle_status USING ((tenant_id = public.get_current_tenant()));


--
-- Name: merchant_note tenant_isolation_policy; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation_policy ON public.merchant_note USING ((tenant_id = public.get_current_tenant()));


--
-- Name: merchant_opportunity_score tenant_isolation_policy; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation_policy ON public.merchant_opportunity_score USING ((tenant_id = public.get_current_tenant()));


--
-- Name: merchant_risk_profile tenant_isolation_policy; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation_policy ON public.merchant_risk_profile USING ((tenant_id = public.get_current_tenant()));


--
-- Name: merchant_segment tenant_isolation_policy; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation_policy ON public.merchant_segment USING ((tenant_id = public.get_current_tenant()));


--
-- Name: merchant_settlement_config tenant_isolation_policy; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation_policy ON public.merchant_settlement_config USING ((tenant_id = public.get_current_tenant()));


--
-- Name: ref_tenant_bin_bank tenant_isolation_policy; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation_policy ON public.ref_tenant_bin_bank USING ((tenant_id = public.get_current_tenant()));


--
-- Name: revenue_leakage_flags tenant_isolation_policy; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation_policy ON public.revenue_leakage_flags USING ((tenant_id = public.get_current_tenant()));


--
-- Name: sales_agent_target tenant_isolation_policy; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation_policy ON public.sales_agent_target USING ((tenant_id = public.get_current_tenant()));


--
-- Name: stg_dcc_revenue_raw tenant_isolation_policy; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation_policy ON public.stg_dcc_revenue_raw USING ((tenant_id = public.get_current_tenant()));


--
-- Name: stg_merchant_master_raw tenant_isolation_policy; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation_policy ON public.stg_merchant_master_raw USING ((tenant_id = public.get_current_tenant()));


--
-- Name: stg_rental_raw tenant_isolation_policy; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation_policy ON public.stg_rental_raw USING ((tenant_id = public.get_current_tenant()));


--
-- Name: stg_trnx_raw tenant_isolation_policy; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation_policy ON public.stg_trnx_raw USING ((tenant_id = public.get_current_tenant()));


--
-- Name: sum_daily_bank tenant_isolation_policy; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation_policy ON public.sum_daily_bank USING ((tenant_id = public.get_current_tenant()));


--
-- Name: sum_daily_channel tenant_isolation_policy; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation_policy ON public.sum_daily_channel USING ((tenant_id = public.get_current_tenant()));


--
-- Name: sum_daily_explorer tenant_isolation_policy; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation_policy ON public.sum_daily_explorer USING ((tenant_id = public.get_current_tenant()));


--
-- Name: sum_daily_finance tenant_isolation_policy; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation_policy ON public.sum_daily_finance USING ((tenant_id = public.get_current_tenant()));


--
-- Name: sum_daily_finance_rollup tenant_isolation_policy; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation_policy ON public.sum_daily_finance_rollup USING ((tenant_id = public.get_current_tenant()));


--
-- Name: sum_daily_full tenant_isolation_policy; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation_policy ON public.sum_daily_full USING ((tenant_id = public.get_current_tenant()));


--
-- Name: sum_daily_insight tenant_isolation_policy; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation_policy ON public.sum_daily_insight USING ((tenant_id = public.get_current_tenant()));


--
-- Name: sum_daily_local_debit_bin tenant_isolation_policy; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation_policy ON public.sum_daily_local_debit_bin USING ((tenant_id = public.get_current_tenant()));


--
-- Name: sum_daily_mcc tenant_isolation_policy; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation_policy ON public.sum_daily_mcc USING ((tenant_id = public.get_current_tenant()));


--
-- Name: sum_daily_merchant tenant_isolation_policy; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation_policy ON public.sum_daily_merchant USING ((tenant_id = public.get_current_tenant()));


--
-- Name: sum_daily_merchant_attribute tenant_isolation_policy; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation_policy ON public.sum_daily_merchant_attribute USING ((tenant_id = public.get_current_tenant()));


--
-- Name: sum_daily_merchant_destination tenant_isolation_policy; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation_policy ON public.sum_daily_merchant_destination USING ((tenant_id = public.get_current_tenant()));


--
-- Name: sum_daily_scheme tenant_isolation_policy; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation_policy ON public.sum_daily_scheme USING ((tenant_id = public.get_current_tenant()));


--
-- Name: sum_daily_terminal tenant_isolation_policy; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation_policy ON public.sum_daily_terminal USING ((tenant_id = public.get_current_tenant()));


--
-- Name: sum_monthly_bank tenant_isolation_policy; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation_policy ON public.sum_monthly_bank USING ((tenant_id = public.get_current_tenant()));


--
-- Name: sum_monthly_card tenant_isolation_policy; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation_policy ON public.sum_monthly_card USING ((tenant_id = public.get_current_tenant()));


--
-- Name: sum_monthly_insight tenant_isolation_policy; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation_policy ON public.sum_monthly_insight USING ((tenant_id = public.get_current_tenant()));


--
-- Name: sum_monthly_merchant_metrics tenant_isolation_policy; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation_policy ON public.sum_monthly_merchant_metrics USING ((tenant_id = public.get_current_tenant()));


--
-- Name: tenant_setting tenant_isolation_policy; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation_policy ON public.tenant_setting USING ((tenant_id = public.get_current_tenant()));


--
-- Name: profitability_adjustment_rate tenant_isolation_profit_adj; Type: POLICY; Schema: public; Owner: -
--

CREATE POLICY tenant_isolation_profit_adj ON public.profitability_adjustment_rate USING ((tenant_id = public.get_current_tenant()));


--
-- PostgreSQL database dump complete
--


