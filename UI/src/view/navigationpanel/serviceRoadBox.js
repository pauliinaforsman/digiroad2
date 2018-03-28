(function(root) {
  root.ServiceRoadBox = function (assetConfig) {
    LinearAssetBox.call(this, assetConfig);
    var me = this;

    this.header = function () {
      return assetConfig.title;
    };

    this.legendName = function () {
      return 'linear-asset-legend service-road';
    };

    this.labeling = function () {
      var responsibilityValues = [
        [ 0, 'Tieoikeus'],
        [ 1, 'Tiekunnan osakkuus'],
        [ 2, 'LiVin hallinnoimalla maa-alueella'],
        [ 3, 'Kevyen liikenteen väylä'],
        [ 4, 'Tuntematon']
      ];

      var responsibilityLegend = '<div class="responsibility-legend">' + _.map(responsibilityValues, function(responsibilityValue) {
        return '<div class="legend-entry">' +
            '<div class="label">' + responsibilityValue[1] + '</div>' +
            '<div class="symbol linear service-road-' + responsibilityValue[0] + '" />' +
            '</div>';
      }).join('')+ '</div>';

      var rightOfUseValues = [
        [ 10, 'Livi'],
        [ 11, 'Muu'],
        [ 12, 'Ei tietoa']
      ];

      var rightOfUseLegend = '<div class="right-use-legend">' + _.map(rightOfUseValues, function(rightOfUseValue) {
        return '<div class="legend-entry right-use-legend">' +
            '<div class="label">' + rightOfUseValue[1] + '</div>' +
            '<div class="symbol linear service-road-' + rightOfUseValue[0] + '" />' +
            '</div>';
      }).join('') + '</div>';

      return '<div class="panel-section panel-legend '+ me.legendName() + '-legend">' + rightOfUseLegend + responsibilityLegend + '</div>';
    };

      this.renderTemplate = function () {
          this.expanded = me.elements().expanded;
          $(me.expanded).find('input[type=radio][name=labelingRadioButton]').change(labelingHandler);
          me.eventHandler();
          return me.getElement()
              .append(this.expanded)
              .hide();
      };

    var labelingHandler = function() {
      if (this.value == 'responsibility') {
          $('.right-use-legend').hide();
          $('.responsibility-legend').show();
          eventbus.trigger('serviceRoad:responsibility', true);

        } else {
          $('.right-use-legend').show();
          $('.responsibility-legend').hide();
          eventbus.trigger('serviceRoad:responsibility', false);
        }
    };

    this.checkboxPanel = function () {
      return assetConfig.allowComplementaryLinks ? [
          '<div class="panel-section panel-legend '+ me.legendName() + '-legend">' +
          '<div class="check-box-container">' +
          '<input id="complementaryLinkCheckBox" type="checkbox" /> <lable>Näytä täydentävä geometria</lable>' +
          '</div>' +
          '</div>'
        ].join('') : '';
    };

    this.radioButton = function () {
      return [
        '  <div class="panel-section">' +
        '    <div class="radio">' +
        '     <label>' +
        '       <input name="labelingRadioButton" value="responsibility" type="radio" checked> Käyttöoikeus' +
        '     </label>' +
        '     <label>' +
        '       <input name="labelingRadioButton" value="rightOfUse" type="radio"> Huoltovastuu' +
        '     </label>' +
        '    </div>' +
        '  </div>'
            ].join('');
    };

    this.panel = function () {
      return [ '<div class="panel ' + me.layerName() +'">',
        '  <header class="panel-header expanded">',
        me.header() ,
        '  </header>'
        ].join('');
    };

    this.predicate = function () {
      return _.contains(me.roles, 'operator') || _.contains(me.roles, 'premium')  || _.contains(me.roles, 'serviceRoadMaintainer');
    };

    var element = $('<div class="panel-group service-road"/>');

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

    this.getElement = function () {
      return element;
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

