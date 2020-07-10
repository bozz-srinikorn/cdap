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

import withStyles, { StyleRules, WithStyles } from '@material-ui/core/styles/withStyles';
import Tooltip from '@material-ui/core/Tooltip';
import DeleteIcon from '@material-ui/icons/Delete';
import ScheduleIcon from '@material-ui/icons/Schedule';
import classnames from 'classnames';
import { IRequestHistory, RequestMethod } from 'components/HttpExecutor/RequestHistoryTab';
import DeleteDialog from 'components/HttpExecutor/RequestHistoryTab/RequestActionDialogs/DeleteDialog';
import HttpExecutorActions from 'components/HttpExecutor/store/HttpExecutorActions';
import * as React from 'react';
import { connect } from 'react-redux';

const styles = (theme): StyleRules => {
  return {
    requestRow: {
      padding: `10px 0`,
      lineHeight: '24px',
      display: 'flex',
      justifyContent: 'space-between',
      width: '100%',
      cursor: 'pointer',

      '&:hover': {
        backgroundColor: theme.palette.grey[700],
        '& $requestActionButton': {
          visibility: 'visible',
        },
      },
    },
    requestMethod: {
      paddingLeft: '5px',
      color: theme.palette.white[50],
      height: '100%',
      fontWeight: 800,
      justifyContent: 'center',
      alignItems: 'flex-start',
      fontSize: '10px',
      width: '10%',
    },
    requestMethodText: {
      width: '100%',
      textAlign: 'left',
      alignSelf: 'center',
    },
    requestPath: {
      maxWidth: '80%',
      minWidth: '80%',
      wordWrap: 'break-word',
      textAlign: 'left',
      alignSelf: 'center',
      textTransform: 'lowercase',
      fontSize: '10px',
      display: 'inline-block',
      lineHeight: '1.3',
    },
    getMethod: {
      color: theme.palette.green[400],
    },
    postMethod: {
      color: theme.palette.yellow[50],
    },
    putMethod: {
      color: theme.palette.blue[50],
    },
    deleteMethod: {
      color: theme.palette.red[50],
    },
    buttonTooltip: {
      fontSize: '14px',
      backgroundColor: theme.palette.grey[50],
    },
    requestActionButtons: {
      justifyContent: 'flex-end',
      whiteSpace: 'nowrap',
    },
    requestActionButton: {
      display: 'inline-block',
      visibility: 'hidden',
    },
  };
};

const mapDispatch = (dispatch) => {
  return {
    onRequestClick: (request: IRequestHistory) => {
      dispatch({
        type: HttpExecutorActions.setRequestHistoryView,
        payload: request,
      });
    },
  };
};

interface IRequestRowProps extends WithStyles<typeof styles> {
  request: IRequestHistory;
  onRequestClick: (request: IRequestHistory) => void;
}

const RequestRowView: React.FC<IRequestRowProps> = ({ classes, request, onRequestClick }) => {
  const [deleteDialogOpen, setDeleteDialogOpen] = React.useState(false);

  const renderTimestamp = (timestamp: Date) => {
    const dateOptions = { weekday: 'long', year: 'numeric', month: 'long', day: 'numeric' };
    const timeOptions = {
      hour12: true,
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
    };

    return `${timestamp.toLocaleDateString('en-US', dateOptions)} ${timestamp.toLocaleTimeString(
      'en-US',
      timeOptions
    )}`;
  };

  return (
    <div>
      <div className={classes.requestRow} onClick={() => onRequestClick(request)}>
        <div className={classes.requestTimestamp}>
          <Tooltip
            classes={{
              tooltip: classes.buttonTooltip,
            }}
            title={renderTimestamp(request.requestID)}
            placement="right"
            className={classes.requestActionButton}
          >
            <ScheduleIcon />
          </Tooltip>
        </div>
        <div
          className={classnames(classes.requestMethod, {
            [classes.getMethod]: request.method === RequestMethod.GET,
            [classes.postMethod]: request.method === RequestMethod.POST,
            [classes.deleteMethod]: request.method === RequestMethod.DELETE,
            [classes.putMethod]: request.method === RequestMethod.PUT,
          })}
        >
          <div className={classes.requestMethodText}>{request.method}</div>
        </div>
        <div className={classes.requestPath}>{request.path}</div>
        <div className={classes.requestActionButtons}>
          <Tooltip
            classes={{
              tooltip: classes.buttonTooltip,
            }}
            title={'Delete request'}
            placement="bottom"
            className={classes.requestActionButton}
          >
            <DeleteIcon onClick={() => setDeleteDialogOpen(true)} />
          </Tooltip>
        </div>
      </div>
      <DeleteDialog
        requestID={request.requestID}
        open={deleteDialogOpen}
        handleClose={() => setDeleteDialogOpen(false)}
      />
    </div>
  );
};

const RequestRow = withStyles(styles)(connect(null, mapDispatch)(RequestRowView));
export default RequestRow;
