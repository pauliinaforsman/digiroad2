(function(root) {
  root.SpeedLimitBox = function (selectedSpeedLimit) {
    ActionPanelBox.call(this);
    var me = this;

    this.header = function () {
      return 'Nopeusrajoitukset';
    };

    this.title = function (){
      return 'Nopeusrajoitus';
    };

    this.layerName = function () {
      return 'speedLimit';
    };

    this.legendName = function () {
      return 'linear-asset-legend speed-limit';
    };

    this.labeling = function () {
      var speedLimits = [120, 100, 90, 80, 70, 60, 50, 40, 30, 20];
      return  _.map(speedLimits, function(speedLimit) {
        return '<div class="legend-entry">' +
          '<div class="label">' + speedLimit + '</div>' +
          '<div class="symbol linear speed-limit-' + speedLimit + '" />' +
          '</div>';
      }).join('');
    };

    this.checkboxPanel = function () {
      var speedLimitHistoryCheckBox = [
        '<div class="check-box-container">',
        '<input id="historyCheckbox" type="checkbox" /> <lable>Näytä poistuneet tielinkit</lable>' +
        '</div>'].join('');

      var speedLimitComplementaryCheckBox = [
        '<div class="check-box-container">' +
        '<input id="complementaryLinkCheckBox" type="checkbox" /> <lable>Näytä täydentävä geometria</lable>' +
        '</div>'
      ].join('');

      var speedLimitSignsCheckBox = [
        '<div class="check-box-container">' +
        '<input id="trafficSignsCheckbox" type="checkbox" /> <lable>Näytä liikennemerkit</lable>' +
        '</div>' +
        '</div>'
      ].join('');

      return speedLimitHistoryCheckBox.concat(speedLimitComplementaryCheckBox).concat(speedLimitSignsCheckBox);

    };

    this.predicate = function () {
      return _.contains(me.roles, 'operator') || _.contains(me.roles, 'premium');
    };

    this.toolSelection = new me.ToolSelection([
      new me.Tool('Select', me.selectToolIcon, selectedSpeedLimit),
      new me.Tool('Cut', me.cutToolIcon, selectedSpeedLimit)
    ]);

    this.editModeToggle = new EditModeToggleButton(me.toolSelection);

    var element = $('<div class="panel-group speed-limits"/>');

    this.renderTemplate = function () {
      this.expanded = me.elements().expanded;
      myEvents();
      return element
        .append(this.expanded)
        .hide();
    };

    function show() {
      if (me.editModeToggle.hasNoRolesPermission(me.roles)) {
        me.editModeToggle.reset();
      } else {
        me.editModeToggle.toggleEditMode(applicationModel.isReadOnly());
      }
      element.show();
    }

    function hide() {
      element.hide();
    }

    var myEvents = function() {
      me.eventHandler();
      $(me.expanded).find('#historyCheckbox').on('change', function (event) {
        var eventTarget = $(event.currentTarget);
        if (eventTarget.prop('checked')) {
          eventbus.trigger('speedLimits:showSpeedLimitsHistory');
        } else {
          eventbus.trigger('speedLimits:hideSpeedLimitsHistory');
        }
      });
    };

    return {
      title: me.title(),
      layerName: me.layerName(),
      element: me.renderTemplate(),
      show: show,
      hide: hide
    };
  };
})(this);