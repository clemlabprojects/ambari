import React from 'react';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import ConnectorGallery from '../ConnectorGallery';
import { CATALOG_TEMPLATES } from '../trinoCatalogTemplates';

describe('ConnectorGallery', () => {
  it('shows one card per connector with logo, name and tagline, and hands the template back on click', async () => {
    const onPick = jest.fn();
    render(<ConnectorGallery open onPick={onPick} onClose={() => undefined} />);
    for (const t of CATALOG_TEMPLATES) {
      expect(screen.getByRole('button', { name: `connector ${t.label}` })).toBeInTheDocument();
      expect(screen.getAllByText(t.tagline).length).toBeGreaterThan(0); // taglines may repeat (two RDBMS)
    }
    expect(screen.getAllByRole('img')).toHaveLength(CATALOG_TEMPLATES.length);
    await userEvent.click(screen.getByRole('button', { name: 'connector S3 object storage' }));
    expect(onPick).toHaveBeenCalledWith(expect.objectContaining({ id: 's3', connector: 'hive' }));
  });
});
