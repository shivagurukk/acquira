/**
 * Upload page — what a job that has NOT started yet looks like.
 *
 * Real batch concurrency is 2 (batchTaskExecutor is core 2 with a 25-deep
 * queue, so the pool never grows to its max of 6). A third upload is therefore
 * accepted, persisted as STARTING, and then waits — with ZERO step executions,
 * so /api/batch/jobs/{id}/status returns no currentStep and no stepNumber.
 *
 * That state used to be indistinguishable from real work: the stage tracker
 * fell back to matching progress against stage ranges, 0 landed inside
 * [0, 10], and the "Splitting" stage lit up for a job that had not started.
 * Operators read that as a hang in file splitting and re-ran the upload, which
 * only lengthened the queue.
 *
 * These pin the distinction, because the two cases differ ONLY in the absence
 * of a couple of fields — exactly the kind of thing a refactor re-breaks.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';

let mockProgress = null;
const subscribeToJob = vi.fn();

vi.mock('../hooks/useNotifications', () => ({
    default: () => ({
        uploadProgress: mockProgress,
        subscribeToJob,
        isConnected: false,
        reportProgress: null,
    }),
}));

vi.mock('../api/axios', () => ({
    default: { post: vi.fn(), get: vi.fn() },
    UPLOAD_TIMEOUT: 15 * 60 * 1000,
    isTimeoutError: () => false,
}));

import UploadPage from '../pages/UploadPage';

/** Payload shape from BatchProgressController.buildProgressPayload. */
const queued = {
    executionId: 42,
    jobName: 'transactionLoadJob',
    status: 'STARTING',
    readCount: 0, writeCount: 0, skipCount: 0,
    stepNumber: 0,          // no step executions yet
    totalSteps: 12,
    // currentStep deliberately absent — the controller only sets it once a
    // StepExecution exists.
};

const running = {
    ...queued,
    status: 'STARTED',
    currentStep: 'splitExcelStep',
    stepNumber: 2,
    readCount: 1200,
};

beforeEach(() => {
    mockProgress = null;
    subscribeToJob.mockReset();
});

describe('Upload page — queued vs running', () => {
    it('says a job with no step executions is queued, not splitting', () => {
        mockProgress = queued;
        render(<MemoryRouter><UploadPage /></MemoryRouter>);

        expect(screen.getByText('Queued')).toBeTruthy();
        expect(screen.getByText(/waiting for a free batch slot/i)).toBeTruthy();
    });

    it('does not claim a step is running when none has started', () => {
        mockProgress = queued;
        render(<MemoryRouter><UploadPage /></MemoryRouter>);

        // "Splitting file" is the label for splitExcelStep. A queued job must not
        // show any step label — the bare stage name "Splitting" still appears as
        // a tracker caption, but the step readout must not.
        expect(screen.queryByText(/Splitting file/)).toBeNull();
        expect(screen.queryByText(/step 0 of/)).toBeNull();
    });

    it('shows the real step once one is actually running', () => {
        mockProgress = running;
        const { container } = render(<MemoryRouter><UploadPage /></MemoryRouter>);
        const text = container.textContent;

        // "Splitting file" legitimately appears twice — the step readout and the
        // progress-bar label — so assert on the page text rather than a unique node.
        expect(text).toContain('Splitting file · step 2 of 12');
        expect(text).not.toContain('Queued');
    });

    it('clamps the percentage when stepNumber runs past a stale totalSteps', () => {
        // TOTAL_STEPS is a hand-maintained map. When it drifts below the real step
        // count the ratio exceeds 1, which used to render as "133%".
        mockProgress = { ...running, currentStep: 'populateSummaryStep', stepNumber: 12, totalSteps: 9 };
        const { container } = render(<MemoryRouter><UploadPage /></MemoryRouter>);
        const text = container.textContent;

        expect(text).toContain('100%');
        expect(text).not.toMatch(/1[1-9]\d%|[2-9]\d\d%/);
    });
});
