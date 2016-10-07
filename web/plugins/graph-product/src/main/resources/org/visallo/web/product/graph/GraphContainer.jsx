define([
    'react',
    'react-redux',
    'react-dom',
    'data/web-worker/store/selection/actions',
    'data/web-worker/store/product/actions',
    'data/web-worker/store/product/selectors',
    'components/DroppableHOC',
    './Graph'
], function(React, redux, ReactDom, selectionActions, productActions, productSelectors, DroppableHOC, Graph) {
    'use strict';

    const mimeTypes = [VISALLO_MIMETYPES.ELEMENTS];
    const style = { height: '100%' };

    const GraphContainer = redux.connect(

        (state, props) => {
            var viewport = state.product.viewports[props.product.id] || { zoom: 1, pan: {x: 0, y: 0 }},
                pixelRatio = state.screen.pixelRatio;

            return {
                ...props,
                selection: productSelectors.getSelectedElementsInProduct(state),
                viewport,
                pixelRatio,
                elements: productSelectors.getElementsInProduct(state),
                mimeTypes,
                style
            }
        },

        function(dispatch, props) {
            return {
                onAddSelection: (selection) => dispatch(selectionActions.add(selection)),
                onRemoveSelection: (selection) => dispatch(selectionActions.remove(selection)),
                onUpdateSelection: (selection) => dispatch(selectionActions.addRemove(selection)),
                onSetSelection: (selection) => dispatch(selectionActions.set(selection)),
                onClearSelection: () => dispatch(selectionActions.clear),

                onUpdatePreview: (id, dataUrl) => dispatch(productActions.updatePreview(id, dataUrl)),

                // TODO: these should be graphActions
                onUpdatePositions: (id, positions) => dispatch(productActions.updatePositions(id, positions)),
                onUpdateViewport: (id, { pan, zoom }) => dispatch(productActions.updateViewport(id, { pan, zoom })),
                onDrop: (event, position) => {
                    const dataStr = event.dataTransfer.getData(VISALLO_MIMETYPES.ELEMENTS);
                    if (dataStr) {
                        event.preventDefault();
                        event.stopPropagation();

                        const data = JSON.parse(dataStr);
                        dispatch(productActions.dropElements(props.product.id, data.elements, position))
                    }
                }
            }
        },
        null,
        { withRef: true }
    )(DroppableHOC(Graph));

    return GraphContainer;
});