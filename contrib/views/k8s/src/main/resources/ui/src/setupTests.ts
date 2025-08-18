import '@testing-library/jest-dom';
import '@ant-design/v5-patch-for-react-19';

// AntD message/notification utilisent portal : assurer un conteneur root
beforeAll(() => {
  const root = document.createElement('div');
  root.id = 'root';
  document.body.appendChild(root);
});

