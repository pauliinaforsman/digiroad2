define(['chai', 'TestHelpers', 'SpeedLimitLayer', 'SpeedLimitsCollection', 'SelectedSpeedLimit', 'zoomlevels', 'GeometryUtils', 'LinearAsset'],
       function(chai, testHelpers, SpeedLimitLayer, SpeedLimitsCollection, SelectedSpeedLimit, zoomLevels, GeometryUtils, LinearAsset) {
  var assert = chai.assert;

  describe('SpeedLimitLayer', function() {
    describe('when moving map', function() {
      var layer;
      before(function() {
        var speedLimitsCollection = new SpeedLimitsCollection({
          getSpeedLimits: function() {
            eventbus.trigger('speedLimits:fetched', [
              {id: 1, sideCode: 1, links: [{points: [{x: 0, y: 0}], position: 1}]},
              {id: 2, sideCode: 1, links: [{points: [{x: 10, y: 10}], position: 0}]}
            ]);
          }
        });
        var selectedSpeedLimit = new SelectedSpeedLimit(speedLimitsCollection);
        layer = new SpeedLimitLayer({
          map: {
            addControl: function(control) {
              control.handlers.feature.activate = function() {};
            },
            events: {
              register: function() {},
              unregister: function() {}
            }
          },
          application: {
            getSelectedTool: function() { return 'Select'; }
          },
          collection: speedLimitsCollection,
          selectedSpeedLimit: selectedSpeedLimit,
          geometryUtils: new GeometryUtils(),
          linearAsset: new LinearAsset()
        });
        layer.update(9, null);
        eventbus.trigger('map:moved', {selectedLayer: 'speedLimit', bbox: null, zoom: 10});
      });

      it('should contain each speed limit only once', function() {
        var getFirstPointOfFeature = function(feature) {
          return feature.geometry.getVertices()[0];
        };

        assert.equal(testHelpers.getLineStringFeatures(layer.vectorLayer).length, 2);
        assert.equal(getFirstPointOfFeature(testHelpers.getLineStringFeatures(layer.vectorLayer)[0]).x, 0);
        assert.equal(getFirstPointOfFeature(testHelpers.getLineStringFeatures(layer.vectorLayer)[0]).y, 0);
        assert.equal(getFirstPointOfFeature(testHelpers.getLineStringFeatures(layer.vectorLayer)[1]).x, 10);
        assert.equal(getFirstPointOfFeature(testHelpers.getLineStringFeatures(layer.vectorLayer)[1]).y, 10);
      });

      describe('and zooming out', function() {
        before(function() {
          eventbus.trigger('map:moved', {selectedLayer: 'speedLimit', bbox: null, zoom: 8});
        });

        it('hides features', function() {
          assert.equal(testHelpers.getLineStringFeatures(layer.vectorLayer)[0].getVisibility(), false);
        });

        describe('and zooming in', function() {
          before(function() {
            eventbus.trigger('map:moved', {selectedLayer: 'speedLimit', bbox: null, zoom: 9});
          });

          it('should contain speed limits', function() {
            assert.equal(testHelpers.getLineStringFeatures(layer.vectorLayer).length, 2);
          });
        });
      });
    });
  });
});
