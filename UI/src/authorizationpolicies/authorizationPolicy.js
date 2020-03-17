(function(root) {
  root.AuthorizationPolicy = function() {
    var me = this;
    me.userRoles = [];
    me.municipalities = [];
    me.areas = [];
    me.username = {};
    me.fetched = false;

    eventbus.on('roles:fetched', function(userInfo) {
      me.username = userInfo.username;
      me.userRoles = userInfo.roles;
      me.municipalities = userInfo.municipalities;
      me.areas = userInfo.areas;
      me.fetched = true;
    });

    this.isUser = function(role) {
      return _.includes(me.userRoles, role);
    };

    this.isOnlyUser = function(role) {
      return _.includes(me.userRoles, role) && me.userRoles.length === 1;
    };

    this.isMunicipalityMaintainer = function(){
      return _.isEmpty(me.userRoles) || me.isOnlyUser('premium');
    };

    this.isElyMaintainer = function(){
      return me.isUser('elyMaintainer');
    };

    this.isOperator = function(){
      return me.isUser('operator');
    };

    this.isServiceRoadMaintainer = function(){
      return me.isUser('serviceRoadMaintainer');
    };

    this.hasRightsInMunicipality = function(municipalityCode){
      return _.includes(me.municipalities, municipalityCode);
    };

    this.hasRightsInArea = function(area){
      return _.includes(me.areas, area);
    };

    this.filterRoadLinks = function(roadLink){
      return (me.isMunicipalityMaintainer() && roadLink.administrativeClass !== 'State' && me.hasRightsInMunicipality(roadLink.municipalityCode)) || (me.isElyMaintainer() && me.hasRightsInMunicipality(roadLink.municipalityCode)) || me.isOperator();
    };

    this.editModeAccess = function() {
      return (!me.isUser('viewer') && !me.isOnlyUser('serviceRoadMaintainer'));
    };

    this.editModeTool = function(toolType, asset, roadLink) {};

    this.formEditModeAccess = function() {
      return me.isOperator();
    };

    this.workListAccess = function(){
      return me.isOperator();
    };

    this.isState = function(selectedInfo){
      return selectedInfo.administrativeClass === "State" || selectedInfo.administrativeClass === 1;
    };

    this.validateMultiple = function(selectedAssets) {
      return _.every(selectedAssets, function(selectedAsset){
        return me.formEditModeAccess(selectedAsset);
      });
    };

    this.handleSuggestedAsset = function(selectedAsset, suggestedBoxValue) {
      return (selectedAsset.isNew() && me.isOperator()) || (suggestedBoxValue && (me.isOperator() || me.isMunicipalityMaintainer()));
    };

    this.isMunicipalityExcluded = function (selectedAsset) {
      var municipalitiesExcluded = [478,60,65,76,170,736,771,43,417,438,35,62,295,318,766,941];
      var isOperatorAndHaveRights = me.isOperator() && me.hasRightsInMunicipality(selectedAsset.municipalityCode);

      return municipalitiesExcluded.indexOf(selectedAsset.municipalityCode) >= 0 && isOperatorAndHaveRights;
    };

  };
})(this);