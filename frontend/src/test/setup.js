import '@testing-library/jest-dom';
import { configure } from '@testing-library/react';

// findBy*/waitFor default to 1s. The first test in each dashboard file also
// pays the cold import of a large page module, which exceeds 1s when the files
// run in parallel on a busy machine - a different "first test" failed on each
// run. Give async queries headroom; passing tests are not slowed down.
configure({ asyncUtilTimeout: 15000 });
