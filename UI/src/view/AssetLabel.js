(function(root) {
    root.AssetLabel = function() {
        var me = this;
        this.populatedPoints = [];
        this.MIN_DISTANCE = 0;

        this.getCoordinates = function(points){
            return _.map(points, function(point) {
                return [point.x, point.y];
            });
        };

        this.getCoordinate = function(point){
          return (!_.isUndefined(point.x) ? [point.x, point.y] : [point.lon, point.lat]);
        };

        this.createFeature = function(point){
          if(_.isArray(point))
            return new ol.Feature(new ol.geom.Point(point));
          return new ol.Feature(new ol.geom.Point(me.getCoordinate(point)));
        };

        this.renderFeaturesByPointAssets = function(pointAssets, zoomLevel){
            return me.renderFeatures(pointAssets, zoomLevel, function(asset){
              return me.getCoordinate(asset);
            });
        };

        this.renderFeaturesByLinearAssets = function(linearAssets, zoomLevel){
            return me.renderFeatures(linearAssets, zoomLevel, function(asset){
                var coordinates = me.getCoordinates(me.getPoints(asset));
                var lineString = new ol.geom.LineString(coordinates);
                return GeometryUtils.calculateMidpointOfLineString(lineString);
            });
        };

        this.renderFeatures = function(assets, zoomLevel, getPoint){
          if(!me.isVisibleZoom(zoomLevel))
            return [];

          return _.chain(assets).
          map(function(asset){
            var assetValue = me.getValue(asset);
            if(assetValue !== undefined){
              var style = me.getStyle(assetValue);
              var feature = me.createFeature(getPoint(asset));
              feature.setProperties(asset);
              feature.setStyle(style);
              return feature;
            }
          }).
          filter(function(feature){ return feature !== undefined; })
            .value();
        };

      this.getCoordinateForGrouping = function (point){
        var assetCoordinate = {lon : point.lon, lat : point.lat};
        var assetCounter = {counter: 1};
        if(_.isEmpty(me.populatedPoints)){
          me.populatedPoints.push({coordinate: assetCoordinate, counter: 1});
        }else{
          var populatedPoint = _.find(me.populatedPoints, function (p) {
            return me.isInProximity(point, p, me.MIN_DISTANCE);
          });
          if (!_.isUndefined(populatedPoint)) {
            assetCoordinate = populatedPoint.coordinate;
            assetCounter.counter = populatedPoint.counter + 1;
            populatedPoint.counter++;
          } else {
            me.populatedPoints.push({coordinate: assetCoordinate, counter: 1});
          }
        }
        return [[assetCoordinate.lon, assetCoordinate.lat], assetCounter.counter];
      };

      this.renderGroupedFeatures = function(assets, zoomLevel, getPoint){
        if(!this.isVisibleZoom(zoomLevel))
          return [];

        return _.chain(assets).
        map(function(asset){
          var value = me.getValue(asset);
          var assetLocation = getPoint(asset);
          if(value !== undefined){
            var styles = [];
            styles = styles.concat(me.getStyle(value, assetLocation[1]));
            var feature = me.createFeature(assetLocation[0]);
            feature.setStyle(styles);
            feature.setProperties(asset);
            return feature;
          }
        }).
        filter(function(feature){ return !_.isUndefined(feature); }).
        value();
      };

      this.clearPoints = function () {
        me.populatedPoints = [];
      };

        this.getMarkerOffset = function(zoomLevel){
            if(me.isVisibleZoom(zoomLevel))
                return [23, 9];
        };

        this.getMarkerAnchor = function(zoomLevel){
            if(me.isVisibleZoom(zoomLevel))
                return [-0.45, 0.15];
        };

        this.isVisibleZoom = function(zoomLevel){
            return zoomLevel >= 12;
        };

        this.isInProximity = function (pointA, pointB, MIN_DISTANCE) {
          return Math.sqrt(geometrycalculator.getSquaredDistanceBetweenPoints(pointA, pointB.coordinate)) < MIN_DISTANCE;
        };

        this.getPoints = function(asset){ return asset.points; };

        this.getValue = function(asset){};

        this.getStyle = function(value){};

    };
})(this);
