/*
 * Copyright © 2020 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

import { MySecureKeyApi } from 'api/securekey';
import ConfirmationModal from 'components/ConfirmationModal';
import { SecureKeyStatus } from 'components/SecureKeys';
import React from 'react';
import { getCurrentNamespace } from 'services/NamespaceStore';

interface ISecureKeyDeleteProps {
  state: any;
  dispatch: React.Dispatch<any>;
  open: boolean;
  handleClose: () => void;
}

const SecureKeyDelete: React.FC<ISecureKeyDeleteProps> = ({
  state,
  dispatch,
  open,
  handleClose,
}) => {
  const { secureKeys, activeKeyIndex } = state;

  const deleteSecureKey = () => {
    const key = secureKeys.get(activeKeyIndex).get('name');

    const namespace = getCurrentNamespace();
    const params = {
      namespace,
      key,
    };

    MySecureKeyApi.delete(params).subscribe(() => {
      handleClose();
      dispatch({ type: 'SET_ACTIVE_KEY_INDEX', activeKeyIndex: null });
      dispatch({ type: 'SET_SECURE_KEY_STATUS', secureKeyStatus: SecureKeyStatus.Success });
    });
  };

  return (
    <ConfirmationModal
      headerTitle={'Delete secure key'}
      confirmationElem={'Are you sure you want to delete your secure key from your CDAP Account?'}
      confirmButtonText={'Cancel'}
      confirmFn={deleteSecureKey}
      cancelFn={handleClose}
      isOpen={open}
    />
  );
};

export default SecureKeyDelete;
