(function(root) {
  root.SelectedManoeuvreSource = function(collection) {
    var current = null;

    var close = function() {
      if (current) {
        current = null;
        eventbus.trigger('manoeuvres:unselected');
      }
    };

    var open = function(roadLinkId) {
      if (!current || current.roadLinkId !== roadLinkId) {
        close();
        collection.get(roadLinkId, function(roadLink){
          current = roadLink;
          eventbus.trigger('manoeuvres:selected', roadLink);
        });
      }
    };

    var getRoadLinkId = function() {
      return current.roadLinkId;
    };

    var exists = function() {
      return current !== null;
    };

    var addManoeuvreTo = function(destRoadLink) {
      var newManoeuvre = _.merge({}, {sourceRoadLinkId: current.roadLinkId, sourceMmlID:current.mmlId}, destRoadLink);
      collection.addManoeuvre(newManoeuvre);
    };

    var removeManoeuvreTo = function(destRoadLinkId) {
      eventbus.trigger('manoeuvre:changed');
    };

    return {
      close: close,
      open: open,
      getRoadLinkId: getRoadLinkId,
      exists: exists,
      addManoeuvreTo: addManoeuvreTo,
      removeManoeuvreTo: removeManoeuvreTo
    };
  };
})(this);
