import React from 'react';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

// IMPORTANT: mocker le module API
jest.mock('../../api/client');

import {
  getHelmRepos,
  createHelmRepo,
  loginHelmRepo,
  deleteHelmRepo,
} from '../../api/client';

import RepositoriesPage from '../RepositoriesPage'; // <-- adapte le chemin

describe('RepositoriesPage', () => {
  const user = userEvent.setup();

  beforeEach(() => {
    jest.clearAllMocks();
    (getHelmRepos as jest.Mock).mockResolvedValue([]);
  });

  function renderPage() {
    return render(<RepositoriesPage />);
  }

  it('crée un dépôt HTTP anonyme', async () => {
    renderPage();

    // Remplir le formulaire
    await user.type(screen.getByLabelText(/ID/i), 'bitnami');
    await user.type(screen.getByLabelText(/^Nom$/i), 'bitnami');
    // Type = HTTP (si Select a déjà la valeur par défaut, on peut le laisser)
    await user.type(screen.getByLabelText(/^URL$/i), 'https://charts.bitnami.com/bitnami');
    // Auth = anonymous (par défaut suivant ton implémentation, sinon sélectionner)

    // Soumettre
    await user.click(screen.getByRole('button', { name: /Enregistrer/i }));

    await waitFor(() => {
      expect(createHelmRepo).toHaveBeenCalledWith({
        id: 'bitnami',
        name: 'bitnami',
        type: 'HTTP',
        url: 'https://charts.bitnami.com/bitnami',
        authMode: 'anonymous',
        username: undefined,
        secret: undefined,
      });
    });

    // La page rafraîchit la liste
    expect(getHelmRepos).toHaveBeenCalledTimes(2); // 1er load + refresh après création
  });

  it('affiche les erreurs de validation si champs requis manquants', async () => {
    renderPage();

    await user.click(screen.getByRole('button', { name: /Enregistrer/i }));

    // createHelmRepo ne doit pas être appelé
    await waitFor(() => {
      expect(createHelmRepo).not.toHaveBeenCalled();
    });

    // AntD affiche les messages de rules ; adapte le texte si tu as des messages custom
    expect(screen.getByText(/id is required/i)).toBeInTheDocument();
    expect(screen.getByText(/name is required/i)).toBeInTheDocument();
    expect(screen.getByText(/url is required/i)).toBeInTheDocument();
  });

  it('login/sync appelé sur clic du bouton', async () => {
    (getHelmRepos as jest.Mock).mockResolvedValueOnce([
      { id: 'bitnami', name: 'bitnami', type: 'HTTP', url: 'https://charts.bitnami.com/bitnami', authMode: 'anonymous', authInvalid: false },
    ]);

    renderPage();

    // Le bouton est dans la table, trouve la ligne "bitnami"
    const loginBtn = await screen.findByRole('button', { name: /Login\/Sync/i });
    await user.click(loginBtn);

    await waitFor(() => {
      expect(loginHelmRepo).toHaveBeenCalledWith('bitnami');
    });

    // refresh après login
    expect(getHelmRepos).toHaveBeenCalledTimes(2);
  });

  it('supprime un dépôt via le bouton Delete + confirmation', async () => {
    (getHelmRepos as jest.Mock).mockResolvedValueOnce([
      { id: 'tmp', name: 'tmp', type: 'HTTP', url: 'https://x/y', authMode: 'anonymous', authInvalid: false },
    ]);

    renderPage();

    const delBtn = await screen.findByRole('button', { name: /Delete/i });
    await user.click(delBtn);

    // Popconfirm apparaît → confirmer
    const okBtn = await screen.findByRole('button', { name: /^OK$|^Ok$|^Confirmer$/i });
    await user.click(okBtn);

    await waitFor(() => {
      expect(deleteHelmRepo).toHaveBeenCalledWith('tmp');
    });

    // refresh après suppression
    expect(getHelmRepos).toHaveBeenCalledTimes(2);
  });

  it('crée un dépôt HTTP basic avec username/secret', async () => {
    renderPage();

    await user.type(screen.getByLabelText(/ID/i), 'priv');
    await user.type(screen.getByLabelText(/^Nom$/i), 'priv');
    // Sélectionne Auth = basic
    const authSelect = screen.getByLabelText(/^Auth$/i);
    await user.click(authSelect);
    await user.click(await screen.findByText(/basic/i));

    await user.type(screen.getByLabelText(/Username/i), 'user1');
    await user.type(screen.getByLabelText(/Password \/ Token/i), 's3cr3t');
    await user.type(screen.getByLabelText(/^URL$/i), 'https://repo.local/helm');

    await user.click(screen.getByRole('button', { name: /Enregistrer/i }));

    await waitFor(() => {
      expect(createHelmRepo).toHaveBeenCalledWith({
        id: 'priv',
        name: 'priv',
        type: 'HTTP',
        url: 'https://repo.local/helm',
        authMode: 'basic',
        username: 'user1',
        secret: 's3cr3t',
      });
    });
  });
});

