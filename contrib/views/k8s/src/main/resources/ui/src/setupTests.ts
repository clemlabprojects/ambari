import '@testing-library/jest-dom';
import '@ant-design/v5-patch-for-react-19';

// AntD message/notification utilisent portal : assurer un conteneur root
beforeAll(() => {
  const root = document.createElement('div');
  root.id = 'root';
  document.body.appendChild(root);
});


// antd (Grid/responsive observers) calls window.matchMedia, which jsdom does not implement.
if (typeof window !== 'undefined' && !window.matchMedia) {
  Object.defineProperty(window, 'matchMedia', {
    writable: true,
    value: (query: string) => ({
      matches: false, media: query, onchange: null,
      addListener: () => undefined, removeListener: () => undefined,
      addEventListener: () => undefined, removeEventListener: () => undefined, dispatchEvent: () => false,
    }),
  });
}
