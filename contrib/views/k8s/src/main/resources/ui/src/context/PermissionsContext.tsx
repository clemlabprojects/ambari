// ui/src/context/PermissionsContext.tsx
import React, { createContext, useState, useEffect } from 'react';
import type { UserPermissions, UserRole } from '../types';
import { getMockPermissions } from '../api/mock';

interface PermissionsContextType {
  permissions: UserPermissions | null;
  loading: boolean;
  // La fonction setRole est conservée pour permettre des tests futurs si besoin,
  // mais elle n'est plus exposée dans l'UI.
  setRole: (role: UserRole) => void;
}

export const PermissionsContext = createContext<PermissionsContextType>({
  permissions: null,
  loading: true,
  setRole: () => {},
});

export const PermissionsProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [permissions, setPermissions] = useState<UserPermissions | null>(null);
  const [loading, setLoading] = useState(true);
  
  // Le rôle sera récupéré de l'API backend. Pour la démo, on le fixe.
  // Pour tester les autres rôles, changez 'ADMIN' par 'OPERATOR' ou 'VIEWER' ici.
  const [role, setRole] = useState<UserRole>('ADMIN'); 

  useEffect(() => {
    const fetchPermissions = async () => {
      setLoading(true);
      // En production, ceci serait un vrai appel API : 
      // const response = await fetch('/api/v1/views/K8S_VIEW/versions/1.0.0/instances/k8s-instance/users/me/permissions');
      // const data = await response.json();
      // setPermissions(data);
      
      // Simulation pour le développement
      console.log(`Simulating permissions fetch for role: ${role}`);
      const mockPermissions = getMockPermissions(role);
      setPermissions(mockPermissions);
      setLoading(false);
    };

    fetchPermissions();
  }, [role]);

  return (
    <PermissionsContext.Provider value={{ permissions, loading, setRole }}>
      {children}
    </PermissionsContext.Provider>
  );
};