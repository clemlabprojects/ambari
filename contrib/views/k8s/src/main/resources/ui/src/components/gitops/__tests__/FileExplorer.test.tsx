/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import React from 'react';
import { fireEvent, render, screen } from '@testing-library/react';
import FileExplorer from '../FileExplorer';
import { blankProfile } from '../profileDraft';
import { repositoryEntries, repositoryTree } from '../virtualRepository';

const nodes = () => {
  const p = blankProfile('environments');
  p.files.push({ path: 'environments/{{ namespace }}/README.md', mode: 'createIfMissing', content: '' });
  return repositoryTree(repositoryEntries(p, { release: 'trino', namespace: 'analytics' }));
};

const Harness: React.FC<{ onCreateFile: (path: string) => string | undefined }> = ({ onCreateFile }) => {
  const [selected, setSelected] = React.useState<string | undefined>();
  return (
    <FileExplorer nodes={nodes()} selectedKey={selected} onSelect={setSelected} editable
      onCreateFile={onCreateFile} onCreateFolder={() => undefined} canDelete={() => true} onDelete={() => undefined}
      badges={() => null} />
  );
};

describe('the files explorer', () => {
  it('keeps the selected folder marked and creates a new file inside it, a / making folders', () => {
    const created: string[] = [];
    render(<Harness onCreateFile={path => { created.push(path); return undefined; }} />);

    const folder = screen.getAllByRole('treeitem').find(r => r.textContent === 'namespace')!;
    fireEvent.click(folder);
    expect(folder).toHaveAttribute('aria-selected', 'true');

    fireEvent.click(screen.getByRole('button', { name: /file-add/i }));
    const name = screen.getByPlaceholderText('values.yaml');
    expect(screen.getByText('environments/{{ namespace }}')).toBeInTheDocument();
    fireEvent.change(name, { target: { value: 'ci/deploy.yaml' } });
    fireEvent.keyDown(name, { key: 'Enter', code: 'Enter', keyCode: 13, charCode: 13 });

    expect(created).toEqual(['environments/{{ namespace }}/ci/deploy.yaml']);
    expect(screen.queryByPlaceholderText('values.yaml')).not.toBeInTheDocument();
  });

  it('inserts a template field into the name, and shows why a file cannot be created', () => {
    render(<Harness onCreateFile={() => 'The profile already has a file with this path.'} />);
    fireEvent.click(screen.getByRole('button', { name: /file-add/i }));
    fireEvent.click(screen.getByText('release'));
    const name = screen.getByPlaceholderText('values.yaml') as HTMLInputElement;
    expect(name.value).toBe('{{ release }}');
    fireEvent.keyDown(name, { key: 'Enter', code: 'Enter', keyCode: 13, charCode: 13 });
    expect(screen.getByText('The profile already has a file with this path.')).toBeInTheDocument();
  });
});

describe('removing from the explorer', () => {
  it('keeps the confirmation open while the pointer leaves the row, and removes on confirm', async () => {
    const removed: string[] = [];
    render(
      <FileExplorer nodes={nodes()} selectedKey={undefined} onSelect={() => undefined} editable
        onCreateFile={() => undefined} onCreateFolder={() => undefined} canDelete={n => !!n.entry && n.entry.kind === 'rule'}
        onDelete={n => removed.push(n.path)} badges={() => null} />,
    );
    const row = screen.getAllByRole('treeitem').find(r => r.textContent === 'README.md')!;
    fireEvent.mouseEnter(row);
    fireEvent.click(screen.getByLabelText('Remove environments/{{ namespace }}/README.md'));
    fireEvent.mouseLeave(row);

    fireEvent.click(await screen.findByRole('button', { name: 'Remove' }));

    expect(removed).toEqual(['environments/{{ namespace }}/README.md']);
  });
});
