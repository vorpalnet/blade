//ALICE Javascript functions

//transition.html

//change microservice name
$( '#microservice-name').change(function() {
	aliceGraph.cellLabelChanged(aliceSelectedCell, $(this).val(), false);
});

//change transition name
$( '#transition-request-method').change(function() {
	aliceGraph.cellLabelChanged(aliceSelectedCell, $(this).val(), false);
});

//$("#transition-tabs").tabs({
//	activate : function(event, ui) {
//		var active = $('#transition-tabs').tabs('option', 'active');
//	}
//});

//microservice.html
$("#microservice-tabs").tabs();
//$("#microservice-tabs").tabs({
//	activate : function(event, ui) {
//		var active = $('#microservice-tabs').tabs('option', 'active');
//	}
//});


$("#ingress-tabs").tabs();


$(document.body).on(
		'click',
		'.add-or-remove',
		function(event) {
			$target = $(event.currentTarget);
			$closest = $target.closest('.dynamic-line');
			if ($target.is('.fa-plus-circle')) {
				$clone = $closest.clone();
				$clone.find('input:text').val('');
				$clone.insertAfter($closest);
				$target.toggleClass('fa-plus-circle fa-minus-circle');
				$target.css('color', 'red');
			} else {
				$closest.remove();
			}
			return false;
		});

// transition.html
// add or remove header regex pattern
$(document.body).on(
		'click',
		'.addPattern',
		function(event) {
			var $target = $(event.currentTarget);
			if ($target.is('.fa-plus-circle')) {
				$target.closest('.patternMatch').clone().insertAfter('.patternMatch:last');
				$target.toggleClass('fa-plus-circle fa-minus-circle');
				$target.css('color', 'red');
			} else {
				$target.closest('.patternMatch').remove();
			}
			return false;
		});

// transition.html
// auto suggest subscriber uri header for routing region
$(document.body).on('click', '#region', function(event) {
	var $target = $(event.currentTarget);
	var region = $target.val();
	if (region == 'Originating') {
		$('#subscriberUri').val('From');
	} else if (region == 'Terminating') {
		$('#subscriberUri').val('To');
	} else if (region == 'Neutral') {
		$('#subscriberUri').val('');
	}
	return false;
});
