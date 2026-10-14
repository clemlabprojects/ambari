import {
  chartVersionAtLeast,
  formatJavaSize,
  parseJavaSize,
  parseQuantity,
  resourceCheckError,
  resourceCheckErrors,
} from '../resourceChecks';
import { applyBlankValues } from '../blankValues';

const GiB = 2 ** 30;
const MiB = 2 ** 20;
const heap = { kind: 'javaHeap', limitField: 'coordinator.resources.limits.memory' } as const;
const memRequest = { kind: 'memoryRequest', limitField: 'coordinator.resources.limits.memory' } as const;
const cpuRequest = { kind: 'cpuRequest', limitField: 'coordinator.resources.limits.cpu' } as const;

describe('Kubernetes quantities', () => {
  it('reads binary, decimal, milli and exponent forms', () => {
    expect(parseQuantity('8Gi')).toBe(8 * GiB);
    expect(parseQuantity('512Mi')).toBe(512 * MiB);
    expect(parseQuantity('8G')).toBe(8e9);
    expect(parseQuantity('2')).toBe(2);
    expect(parseQuantity('500m')).toBe(0.5);
    expect(parseQuantity('1e9')).toBe(1e9);
    expect(parseQuantity('1.5Gi')).toBe(1.5 * GiB);
    expect(parseQuantity(2)).toBe(2);
  });

  it('compares milli-units exactly with their decimal form', () => {
    expect(parseQuantity('1100m')).toBe(parseQuantity('1.1'));
  });

  it('refuses what Kubernetes refuses', () => {
    for (const bad of ['8GB', '8 Gi', 'eight', '', '-1Gi', 'Gi', undefined, null]) {
      expect(parseQuantity(bad)).toBeNull();
    }
  });
});

describe('Java heap sizes', () => {
  it('reads -Xmx sizes in powers of 1024', () => {
    expect(parseJavaSize('6G')).toBe(6 * GiB);
    expect(parseJavaSize('6g')).toBe(6 * GiB);
    expect(parseJavaSize('6144M')).toBe(6 * GiB);
    expect(parseJavaSize('6291456k')).toBe(6 * GiB);
    expect(parseJavaSize('1073741824')).toBe(GiB);
  });

  it('refuses what Java would not start with', () => {
    for (const bad of ['6Gi', '6.5G', '6 G', '0', '0G', 'G', '', '-6G', undefined]) {
      expect(parseJavaSize(bad)).toBeNull();
    }
  });

  it('formats a suggestion the way an operator types it', () => {
    expect(formatJavaSize(6 * GiB)).toBe('6G');
    expect(formatJavaSize(0.75 * 5 * GiB)).toBe('3840M');
  });
});

describe('resourceCheckError: Java heap against the memory limit', () => {
  it('accepts a blank heap: the chart sizes it from the limit', () => {
    expect(resourceCheckError(heap, '', '8Gi')).toBeNull();
    expect(resourceCheckError(heap, undefined, '8Gi')).toBeNull();
    expect(resourceCheckError(heap, '   ', '8Gi')).toBeNull();
  });

  it('accepts a heap at 70-80% of the limit', () => {
    expect(resourceCheckError(heap, '6G', '8Gi')).toBeNull();
    expect(resourceCheckError(heap, '3G', '4Gi')).toBeNull();
    expect(resourceCheckError(heap, '6800M', '8Gi')).toBeNull(); // 83%
  });

  it('rejects a heap of 85% of the limit or more, and suggests 75%', () => {
    const atLimit = resourceCheckError(heap, '8G', '8Gi');
    expect(atLimit).toContain('below 85% of the limit');
    expect(atLimit).toContain('for example 6G');
    expect(resourceCheckError(heap, '7G', '8Gi')).not.toBeNull(); // 87.5%
    expect(resourceCheckError(heap, '6964M', '8Gi')).not.toBeNull(); // just above 85%
    expect(resourceCheckError(heap, '3500M', '4Gi')).toContain('for example 3G'); // 85.4%
    expect(resourceCheckError(heap, '6G', '4Gi')).not.toBeNull(); // the limit was lowered, the heap was not
  });

  it('rejects a size Java does not understand, whatever the limit', () => {
    expect(resourceCheckError(heap, '6Gi', '8Gi')).toContain('not 6Gi');
    expect(resourceCheckError(heap, '5.5G', '')).toContain('is not a Java heap size');
  });

  it('cannot compare with a limit it cannot read', () => {
    expect(resourceCheckError(heap, '64G', '')).toBeNull();
    expect(resourceCheckError(heap, '64G', 'lots')).toBeNull();
  });
});

describe('resourceCheckError: requests against limits', () => {
  it('accepts a request equal to or below its limit', () => {
    expect(resourceCheckError(memRequest, '8Gi', '8Gi')).toBeNull();
    expect(resourceCheckError(memRequest, '4Gi', '8Gi')).toBeNull();
    expect(resourceCheckError(memRequest, '8G', '8Gi')).toBeNull();
    expect(resourceCheckError(cpuRequest, '2', '2')).toBeNull();
    expect(resourceCheckError(cpuRequest, '1100m', '1.1')).toBeNull();
    expect(resourceCheckError(cpuRequest, 2, '2000m')).toBeNull();
  });

  it('rejects a request above its limit, which Kubernetes would refuse', () => {
    expect(resourceCheckError(memRequest, '8Gi', '4Gi')).toBe(
      'The memory request (8Gi) cannot be higher than the memory limit (4Gi): Kubernetes would refuse the pod. '
      + 'Set it equal to the limit, as by default, or lower.');
    expect(resourceCheckError(cpuRequest, '2500m', '2')).toContain('CPU request (2500m)');
  });

  it('rejects a quantity Kubernetes does not understand', () => {
    expect(resourceCheckError(memRequest, '8GB', '8Gi')).toContain('for example 8Gi or 512Mi');
    expect(resourceCheckError(cpuRequest, 'two', '2')).toContain('for example 2 or 500m');
  });
});

describe('resourceCheckErrors over a form', () => {
  const form = [{
    name: 'coordinatorResources', label: 'Coordinator resources', type: 'group', fields: [
      { name: 'coordinator.resources.requests.memory', label: 'Memory request', type: 'string', resourceCheck: memRequest },
      { name: 'coordinator.resources.limits.memory', label: 'Memory limit', type: 'string' },
      { name: 'coordinator.jvm.maxHeapSize', label: 'Java heap size (optional)', type: 'string', resourceCheck: heap },
    ],
  }];

  it('names the group and the field of each failure', () => {
    const values = { coordinator: { resources: { requests: { memory: '8Gi' }, limits: { memory: '4Gi' } }, jvm: { maxHeapSize: '4G' } } };
    const errors = resourceCheckErrors(form, values);
    expect(errors).toHaveLength(2);
    expect(errors[0]).toMatch(/^Coordinator resources - Memory request: The memory request/);
    expect(errors[1]).toMatch(/^Coordinator resources - Java heap size \(optional\): A 4G heap/);
  });

  it('is empty when every value fits its limit', () => {
    const values = { coordinator: { resources: { requests: { memory: '8Gi' }, limits: { memory: '8Gi' } } } };
    expect(resourceCheckErrors(form, values)).toEqual([]);
  });

  // The last guard on what is sent: an empty heap present in the values replaces the chart's default, and only a
  // chart that sizes the heap from the memory limit understands it. Older ones render a bare -Xmx.
  describe('with the chart version of the deploy, an empty Java heap', () => {
    const heapForm = [{
      name: 'coordinator.jvm.maxHeapSize', label: 'Heap', type: 'string',
      resourceCheck: { ...heap, blankSizedByChartFrom: '1.43.15' },
    }];
    const withHeap = (jvm: any): any => ({ coordinator: { jvm, resources: { limits: { memory: '4Gi' } } } });

    it('is refused when the chart may not understand it', () => {
      for (const empty of ['', '  ', null]) {
        const errors = resourceCheckErrors(heapForm, withHeap({ maxHeapSize: empty }), '1.43.14');
        expect(errors).toEqual(['Heap: An empty Java heap size is only understood by chart 1.43.15 and later, and '
          + 'this deploy uses chart 1.43.14: with an older chart, Java does not start. Enter a heap size (for example '
          + '3G) or remove the key.']);
      }
      for (const unknown of ['', 'latest']) {
        const [error] = resourceCheckErrors(heapForm, withHeap({ maxHeapSize: '' }), unknown);
        expect(error).toContain('An empty Java heap size is only understood by chart 1.43.15');
      }
      expect(resourceCheckErrors(heapForm, withHeap({ maxHeapSize: '' }), '')[0])
        .toContain('this deploy does not name a chart version');
    });

    it('is accepted by a chart that sizes it from the memory limit', () => {
      for (const version of ['1.43.15', '1.44.0']) {
        expect(resourceCheckErrors(heapForm, withHeap({ maxHeapSize: '' }), version)).toEqual([]);
        expect(resourceCheckErrors(heapForm, withHeap({ maxHeapSize: null }), version)).toEqual([]);
      }
    });

    it('is not an issue when the key is absent: the chart keeps its own default', () => {
      expect(resourceCheckErrors(heapForm, withHeap({}), '1.43.14')).toEqual([]);
      expect(resourceCheckErrors(heapForm, withHeap(undefined), '1.43.14')).toEqual([]);
    });

    it('is only checked when the chart version is given: a blank form field is resolved before the deploy', () => {
      expect(resourceCheckErrors(heapForm, withHeap({ maxHeapSize: '' }))).toEqual([]);
    });

    it('does not change how an explicit heap is checked', () => {
      expect(resourceCheckErrors(heapForm, withHeap({ maxHeapSize: '3G' }), '1.43.14')).toEqual([]);
      expect(resourceCheckErrors(heapForm, withHeap({ maxHeapSize: '4G' }), '1.43.15')[0]).toContain('A 4G heap');
    });
  });
});

describe('chartVersionAtLeast', () => {
  it('compares major, minor and patch as numbers', () => {
    expect(chartVersionAtLeast('1.43.15', '1.43.15')).toBe(true);
    expect(chartVersionAtLeast('1.43.16', '1.43.15')).toBe(true);
    expect(chartVersionAtLeast('1.44.0', '1.43.15')).toBe(true);
    expect(chartVersionAtLeast('2.0', '1.43.15')).toBe(true);
    expect(chartVersionAtLeast('1.43.14', '1.43.15')).toBe(false);
    expect(chartVersionAtLeast('1.43.9', '1.43.15')).toBe(false); // not compared as text
    expect(chartVersionAtLeast('1.43', '1.43.15')).toBe(false);
  });

  it('reads the forms a version field may hold', () => {
    expect(chartVersionAtLeast(' v1.43.15 ', '1.43.15')).toBe(true);
    expect(chartVersionAtLeast('1.43.15-rc.1', '1.43.15')).toBe(true);
  });

  it('is unknown for anything that does not name one chart version', () => {
    for (const unknown of ['', '   ', 'latest', '>=1.43.0', '~1.43', undefined, null]) {
      expect(chartVersionAtLeast(unknown, '1.43.15')).toBeNull();
    }
  });
});

describe('applyBlankValues', () => {
  const form = [{
    name: 'g', label: 'G', type: 'group', fields: [
      { name: 'coordinator.jvm.maxHeapSize', label: 'Heap', type: 'string', omitWhenBlank: true },
      { name: 'coordinator.resources.limits.memory', label: 'Limit', type: 'string' },
      { name: 'other', label: 'Other', type: 'string' },
    ],
  }];

  it('leaves a blank optional key out so the chart default applies', () => {
    const values: any = { coordinator: { jvm: { maxHeapSize: ' ' }, resources: { limits: { memory: '8Gi' } } }, other: '' };
    applyBlankValues(form, values);
    expect(values.coordinator.jvm).toEqual({});
    expect(values.coordinator.resources.limits.memory).toBe('8Gi');
    expect(values.other).toBe(''); // not marked: sent as typed
  });

  it('keeps an explicit value', () => {
    const values: any = { coordinator: { jvm: { maxHeapSize: '6G' } } };
    applyBlankValues(form, values);
    expect(values.coordinator.jvm.maxHeapSize).toBe('6G');
  });

  describe('a blank Java heap and the chart version deployed', () => {
    const heapForm = [{
      name: 'coordinator.jvm.maxHeapSize', label: 'Heap', type: 'string', omitWhenBlank: true,
      resourceCheck: { ...heap, blankSizedByChartFrom: '1.43.15' },
    }];
    const withLimit = (memory: string | undefined, maxHeapSize?: string): any => ({
      coordinator: { jvm: maxHeapSize === undefined ? {} : { maxHeapSize }, resources: { limits: { memory } } },
    });

    it('is left to a chart that sizes it from the memory limit', () => {
      for (const version of ['1.43.15', '1.44.0']) {
        const values = withLimit('8Gi', '');
        applyBlankValues(heapForm, values, version);
        expect(values.coordinator.jvm).toEqual({});
      }
    });

    it('gets 75% of the memory limit on an older chart, whose own default is a fixed heap', () => {
      const values = withLimit('8Gi', '');
      applyBlankValues(heapForm, values, '1.43.14');
      expect(values.coordinator.jvm.maxHeapSize).toBe('6G');

      const lowered = withLimit('4Gi', '  ');
      applyBlankValues(heapForm, lowered, '1.43.14');
      expect(lowered.coordinator.jvm.maxHeapSize).toBe('3G');
      expect(resourceCheckError(heap, lowered.coordinator.jvm.maxHeapSize, '4Gi')).toBeNull();

      const absent = withLimit('5Gi');
      applyBlankValues(heapForm, absent, '1.43.0');
      expect(absent.coordinator.jvm.maxHeapSize).toBe('3840M');
    });

    it('gets 75% of the limit too when the chart version is unknown: an explicit heap works with every chart', () => {
      for (const version of [undefined, '', 'latest', '>=1.43.0']) {
        const values = withLimit('8Gi', '');
        applyBlankValues(heapForm, values, version);
        expect(values.coordinator.jvm.maxHeapSize).toBe('6G');
      }
    });

    it('is left out when there is no limit to size it from', () => {
      for (const memory of [undefined, '', 'lots']) {
        const values = withLimit(memory, '');
        applyBlankValues(heapForm, values, '1.43.14');
        expect(values.coordinator.jvm).toEqual({});
      }
    });

    it('never replaces an explicit heap', () => {
      const values = withLimit('8Gi', '5G');
      applyBlankValues(heapForm, values, '1.43.14');
      expect(values.coordinator.jvm.maxHeapSize).toBe('5G');
    });
  });
});
