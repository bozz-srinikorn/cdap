/*
 * Flow Config Controller
 */

define(['core/controllers/runnable-config'], function (RunnableConfigController) {

	var Controller = RunnableConfigController.extend({

		/*
		 * This syntax makes the FlowStatus controller available to this controller.
		 * This allows us to access the flow model that has already been loaded.
		 *
		 * RunnableConfigController uses this value to do its work. Take a look there.
		 *
		 */
		needs: ['FlowStatus']

	});

	Controller.reopenClass({

		type: 'FlowStatusConfig',
		kind: 'Controller'

	});

	return Controller;

});