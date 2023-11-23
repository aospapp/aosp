import json

from acts.libs.test_binding.binding import Binding


def _metric_binding_base(metric, binding_name, bind_to_arg=True,
                         arg_modifier=None, before=None,
                         after=None):
    """The base decorator for creating a binding between a test and a metric.

    Args:
        metric: The metric to use.
        binding_name: The name of the metric binding. This will be the metric
            group displayed in the summary.
        bind_to_arg: If True, the metric will be bound to an argument of
            binding_name on the target.
        arg_modifier: The function to use as a argument modifier. Must be of
            format (inner, *arg, **kwarg) => (*newArg, **newKwargs).
        before: The function to call before the test. Must be of format
            (inner, instance, *arg, **kwarg) => void
        after: The function to call after the test. Must be of format
            (inner, result, *args, **kwargs) => void

    Returns:
        The method that will create the binding.
    """

    def _arg_modifier(inner, *args, **kwargs):
        if binding_name in kwargs:
            raise ValueError(
                'Cannot have two bindings with name %s.' % binding_name)

        new_kwargs = dict(kwargs)
        if bind_to_arg:
            new_kwargs[binding_name] = metric

        if arg_modifier:
            return arg_modifier(*args, **new_kwargs)
        else:
            return args, new_kwargs

    def _inner(func):
        return Binding(inner=func, arg_modifier=_arg_modifier, before=_before,
                       after=_after)

    def _before(inner, instance, *args, **kwargs):
        metric.setup(instance, inner, binding_name)
        if before:
            before(inner, instance, *args, **kwargs)

    def _after(inner, result, *args, **kwargs):
        if after:
            after(inner, result, *args, **kwargs)

        metric.finish()

    return _inner


def metric_binding(metric, binding_name, bind_to_arg=True):
    """Decorator that will bind a metric to a kwarg.

    Args:
        metric: The metric to bind.
        binding_name: The name the metric should be recorded under.
        bind_to_arg: If true, the metric object will be passed in as a named
            argument with the same name as binding_name.

    Returns:
        The method that will create the binding.
    """
    return _metric_binding_base(metric, binding_name, bind_to_arg=bind_to_arg)


def auto_passive_metric(metric, binding_name, bind_to_arg=True):
    """Decorator that will bind a metric to auto run with a test.

    Args:
        metric: The metric to bind.
        binding_name: The name the metric should be recorded under.
        bind_to_arg: If true, the metric object will be passed in as a named
            argument with the same name as binding_name.

    Returns:
        The method that will create the binding.
    """

    def _before(inner, *args, **kwargs):
        metric.start()

    def _after(inner, result, *args, **kwargs):
        metric.stop()

    return _metric_binding_base(metric, binding_name, bind_to_arg=bind_to_arg,
                                before=_before,
                                after=_after)
