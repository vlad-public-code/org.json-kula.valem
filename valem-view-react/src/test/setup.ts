import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterEach } from 'vitest';

// Every test renders into the same jsdom document; without this a query in one test can match a
// node another test left behind, which shows up as a mysteriously ambiguous selector.
afterEach(cleanup);
